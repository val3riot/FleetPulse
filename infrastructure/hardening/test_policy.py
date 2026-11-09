import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).parent))
from policy import BUILD_REPOSITORIES, RUNTIME_REPOSITORIES, MAX_MIB, scan_result, validate_config, validate_runtime_budget


class PolicyTest(unittest.TestCase):
    def setUp(self):
        self.suffix = ':1.2.3@sha256:' + 'a' * 64
        self.config = {'services': {name: {'mem_limit': 128 * 1024**2,
                                         'cpus': 1, 'ports': [{'host_ip': '127.0.0.1'}]}
                                    for name in MAX_MIB}}
        self.dockerfiles = {}
        for name, service in self.config['services'].items():
            if name in BUILD_REPOSITORIES:
                service['build'] = {'args': {}}
                self.dockerfiles[name] = ''.join(f'ARG {key}={repo}{self.suffix}\n'
                                                for key, repo in BUILD_REPOSITORIES[name].items())
            else:
                service['image'] = RUNTIME_REPOSITORIES[name] + self.suffix
        self.report = {'SchemaVersion': 2, 'ArtifactType': 'container_image',
                       'Metadata': {'ImageID': 'sha256:image'}, 'CreatedAt': '2026-11-09',
                       'Results': [{'Class': 'os-pkgs', 'Vulnerabilities': []}]}

    def test_valid_budgets_and_immutable_images(self):
        self.assertTrue(validate_config(self.config, self.dockerfiles))

    def test_missing_service_mutable_images_and_unbounded_resources_fail(self):
        for field, value in [('image', 'image:latest'), ('mem_limit', 0), ('cpus', 0), ('cpus', 3)]:
            with self.subTest(field=field, value=value):
                config = copy.deepcopy(self.config)
                config['services']['redis'][field] = value
                with self.assertRaises(AssertionError):
                    validate_config(config, self.dockerfiles)
        self.config['services'].pop('redis')
        with self.assertRaisesRegex(AssertionError, 'inventory'):
            validate_config(self.config, self.dockerfiles)

    def test_build_arguments_cannot_override_pins_with_mutable_tags(self):
        api = self.config['services']['fleet-api']
        self.assertTrue(validate_config(self.config, self.dockerfiles))
        api['build']['args']['JAVA_RUNTIME_IMAGE'] = 'eclipse-temurin:latest'
        with self.assertRaisesRegex(AssertionError, 'Unpinned build'):
            validate_config(self.config, self.dockerfiles)

    def test_pinned_forks_and_vendor_rebuilds_are_rejected(self):
        for service in RUNTIME_REPOSITORIES:
            config = copy.deepcopy(self.config)
            config['services'][service]['image'] = 'custom/fork' + self.suffix
            with self.subTest(service=service), self.assertRaisesRegex(AssertionError, 'Unofficial runtime'):
                validate_config(config, self.dockerfiles)
        for service, bases in BUILD_REPOSITORIES.items():
            for key in bases:
                config = copy.deepcopy(self.config)
                config['services'][service]['build']['args'][key] = 'custom/fork' + self.suffix
                with self.subTest(service=service, base=key), self.assertRaisesRegex(AssertionError, 'Unofficial build'):
                    validate_config(config, self.dockerfiles)
        config = copy.deepcopy(self.config)
        config['services']['postgres']['build'] = {'args': {}}
        with self.assertRaisesRegex(AssertionError, 'Vendor runtime'):
            validate_config(config, self.dockerfiles)
        config = copy.deepcopy(self.config)
        config['services']['fleet-api'].pop('build')
        config['services']['fleet-api']['image'] = 'custom/application' + self.suffix
        with self.assertRaisesRegex(AssertionError, 'Application must'):
            validate_config(config, self.dockerfiles)

    def test_public_ports_writable_host_mounts_and_privileged_mode_fail(self):
        for changes in [{'ports': [{'host_ip': '0.0.0.0'}]}, {'privileged': True},
                        {'network_mode': 'host'}, {'volumes': [{'type': 'bind', 'read_only': False}]}]:
            config = copy.deepcopy(self.config)
            config['services']['redis'].update(changes)
            with self.assertRaises(AssertionError):
                validate_config(config, self.dockerfiles)

    def test_daemon_budgets_match_compose_decimal_strings(self):
        service = {'mem_limit': '536870912', 'cpus': 0.5}
        host = {'Memory': 536870912, 'NanoCpus': 500000000, 'Privileged': False}
        validate_runtime_budget('api', service, host)
        for changes in [{'Memory': 0}, {'NanoCpus': 0}, {'Privileged': True}]:
            with self.subTest(changes=changes), self.assertRaises(AssertionError):
                validate_runtime_budget('api', service, {**host, **changes})

    def test_high_and_critical_findings_fail_even_when_no_fix_exists(self):
        for severity in ['HIGH', 'CRITICAL']:
            self.report['Results'][0]['Vulnerabilities'] = [{'Severity': severity}]
            result = scan_result(self.report, 'sha256:image')
            self.assertFalse(result['passes'])
            self.assertEqual(result['counts'][severity], 1)
        self.report['Results'][0]['Vulnerabilities'] = [{'Severity': 'MEDIUM'}]
        self.assertTrue(scan_result(self.report, 'sha256:image')['passes'])

    def test_osless_go_image_requires_explicit_context_and_still_counts_cves(self):
        self.report['Results'] = [{'Class': 'lang-pkgs', 'Type': 'gobinary',
                                   'Vulnerabilities': [{'Severity': 'HIGH'}]}]
        with self.assertRaisesRegex(AssertionError, 'OS package'):
            scan_result(self.report, 'sha256:image')
        result = scan_result(self.report, 'sha256:image', osless_go_image=True)
        self.assertFalse(result['passes'])
        self.assertEqual(result['counts']['HIGH'], 1)
        self.report['Results'][0]['Type'] = 'jar'
        with self.assertRaisesRegex(AssertionError, 'OS package'):
            scan_result(self.report, 'sha256:image', osless_go_image=True)

    def test_malformed_findings_cannot_be_silently_ignored(self):
        for finding in [{}, {'Severity': 'unexpected'}]:
            self.report['Results'][0]['Vulnerabilities'] = [finding]
            with self.assertRaisesRegex(AssertionError, 'Invalid finding'):
                scan_result(self.report, 'sha256:image')

    def test_empty_wrong_identity_or_partial_reports_cannot_pass(self):
        for changes in [{'Results': []}, {'Metadata': {'ImageID': 'wrong'}},
                        {'SchemaVersion': 1}, {'Results': [{'Class': 'lang-pkgs'}]},
                        {'ArtifactType': 'filesystem'}, {'CreatedAt': None}]:
            with self.subTest(changes=changes), self.assertRaises(AssertionError):
                scan_result({**self.report, **changes}, 'sha256:image')


if __name__ == '__main__':
    unittest.main()
