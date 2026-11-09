import copy
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).parent))
from verify import Verification, isolated_config, java_results
from unittest.mock import Mock


class IsolationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.source = {
            'name': 'fleetpulse',
            'volumes': {'data': {'name': 'fleetpulse_data'}},
            'networks': {'default': {'name': 'fleetpulse_default'}},
            'services': {
                'api': {'container_name': 'real-api', 'restart': 'unless-stopped',
                        'build': {'context': str(self.root)}, 'image': 'real-api:latest',
                        'ports': [{'target': 8080, 'published': '8080', 'host_ip': '127.0.0.1'}],
                        'volumes': [{'type': 'bind', 'source': str(self.root / 'config'), 'target': '/config'}]},
                'postgres': {'image': 'postgres:test', 'volumes': [{'type': 'volume', 'source': 'data', 'target': '/data'}]},
            },
        }

    def test_names_ports_and_images_cannot_reuse_the_operator_deployment(self):
        original = copy.deepcopy(self.source)
        config = isolated_config(self.source, self.root)
        self.assertEqual(self.source, original)
        self.assertNotIn('name', config)
        self.assertNotIn('name', config['volumes']['data'])
        self.assertNotIn('name', config['networks']['default'])
        api = config['services']['api']
        self.assertNotIn('container_name', api)
        self.assertNotIn('image', api)
        self.assertEqual(api['restart'], 'no')
        self.assertEqual(api['ports'][0]['published'], '0')
        self.assertEqual(api['ports'][0]['host_ip'], '127.0.0.1')
        self.assertEqual(config['services']['postgres']['image'], 'postgres:test')

    def test_runtime_budgets_are_preserved_by_isolation(self):
        self.source['services']['api'].update(mem_limit=268435456, cpus=0.5)
        config = isolated_config(self.source, self.root)
        self.assertEqual(config['services']['api']['mem_limit'], 268435456)
        self.assertEqual(config['services']['api']['cpus'], 0.5)

    def test_external_networks_and_volumes_are_rejected(self):
        for section in ('networks', 'volumes'):
            config = copy.deepcopy(self.source)
            next(iter(config[section].values()))['external'] = True
            with self.assertRaisesRegex(AssertionError, 'External Docker'):
                isolated_config(config, self.root)

    def test_build_and_bind_paths_must_be_inside_clone(self):
        for kind in ('build', 'bind'):
            config = copy.deepcopy(self.source)
            if kind == 'build':
                config['services']['api']['build']['context'] = str(self.root.parent)
            else:
                config['services']['api']['volumes'][0]['source'] = str(self.root.parent)
            with self.assertRaisesRegex(AssertionError, 'outside clone'):
                isolated_config(config, self.root)

    def test_privileged_host_network_or_secrets_cannot_be_used(self):
        for changes in ({'privileged': True}, {'network_mode': 'host'}):
            config = copy.deepcopy(self.source)
            config['services']['api'].update(changes)
            with self.assertRaisesRegex(AssertionError, 'Unsafe'):
                isolated_config(config, self.root)
        config = copy.deepcopy(self.source)
        config['secrets'] = {'password': {'external': True}}
        with self.assertRaisesRegex(AssertionError, 'Secrets'):
            isolated_config(config, self.root)

    def test_new_anonymous_volumes_are_allowed_but_existing_volumes_are_rejected(self):
        verifier = Verification(self.root / 'evidence')
        verifier.config = {'services': {'kafka': {'volumes': []}}}
        verifier.compose = Mock(return_value='container-id')
        verifier.inspect = Mock(return_value={
            'Config': {'Labels': {'com.docker.compose.project': verifier.project}},
            'Mounts': [{'Type': 'volume', 'Name': 'anonymous-id', 'Destination': '/secrets'}],
            'NetworkSettings': {'Ports': {}},
        })
        verifier.endpoints()
        self.assertEqual(verifier.created_volumes, {'anonymous-id'})
        verifier.existing_volumes = {'anonymous-id'}
        with self.assertRaisesRegex(AssertionError, 'Pre-existing'):
            verifier.endpoints()
        verifier.existing_volumes = set()
        verifier.config['services']['kafka']['volumes'] = [
            {'type': 'volume', 'source': 'data', 'target': '/secrets'}]
        with self.assertRaisesRegex(AssertionError, 'not owned'):
            verifier.endpoints()

    def test_missing_failed_or_skipped_reports_are_not_a_success(self):
        with self.assertRaises(AssertionError):
            java_results(self.root)
        path = self.root / 'module/target/surefire-reports/TEST-sample.xml'
        path.parent.mkdir(parents=True)
        for field in ('failures', 'errors', 'skipped'):
            path.write_text(f'<testsuite tests="2" {field}="1"/>')
            with self.assertRaises(AssertionError):
                java_results(self.root)
        path.write_text('<testsuite tests="2" failures="0" errors="0" skipped="0"/>')
        self.assertEqual(java_results(self.root), dict(tests=2, failures=0, errors=0, skipped=0, suites=1))


if __name__ == '__main__':
    unittest.main()
