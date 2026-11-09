import importlib.util
import os
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).parent))
spec = importlib.util.spec_from_file_location('hardening_verify', Path(__file__).with_name('verify.py'))
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class VerificationTest(unittest.TestCase):
    def test_snapshot_copies_current_sources_preserves_modes_and_fingerprints_content(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'source'
            root.mkdir()
            source = root / 'script'
            source.write_text('original')
            source.chmod(0o755)
            (root / '.env').write_text('private')
            with patch.object(verifier, 'ROOT', root), patch.object(verifier.subprocess, 'check_output', return_value=b'script\0'):
                first = verifier.snapshot(Path(directory) / 'first')
                second = verifier.snapshot(Path(directory) / 'second')
                self.assertEqual(first, second)
                self.assertFalse((Path(directory) / 'first/.env').exists())
                self.assertTrue(os.access(Path(directory) / 'first/script', os.X_OK))
                source.write_text('changed')
                self.assertNotEqual(first, verifier.snapshot(Path(directory) / 'third'))

    def test_snapshot_rejects_private_files_or_symlinks_outside_repository(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'source'
            root.mkdir()
            (root / '.env').write_text('private')
            (root / '.env.local').write_text('private')
            outside = Path(directory) / 'outside'
            outside.write_text('not source')
            (root / 'link').symlink_to(outside)
            for entry in ['.env', '.env.local', 'link']:
                with self.subTest(entry=entry), patch.object(verifier, 'ROOT', root), patch.object(verifier.subprocess, 'check_output', return_value=(entry + '\0').encode()), self.assertRaises(AssertionError):
                    verifier.snapshot(Path(directory) / ('output-' + entry))

    def test_readiness_waits_for_jobs_and_requires_successful_exit(self):
        verification = Mock()
        verification.endpoints.return_value = ({'api': 'url'}, {'flyway': {'State': {'Status': 'running'}}})
        self.assertIsNone(verifier.ready(verification))
        verification.endpoints.return_value[1]['flyway']['State'] = {'Status': 'exited', 'ExitCode': 1}
        with self.assertRaisesRegex(AssertionError, 'Initialization failed'):
            verifier.ready(verification)
        verification.endpoints.return_value[1]['flyway']['State']['ExitCode'] = 0
        self.assertEqual(verifier.ready(verification), {'api': 'url'})

    def test_scans_disable_local_filters_and_collect_all_vulnerable_images(self):
        with tempfile.TemporaryDirectory() as directory:
            verification = Mock()
            verification.output = Path(directory)
            def run(args, *unused, **kwargs):
                if '--version' in args:
                    return '{}'
                report = {'SchemaVersion': 2, 'ArtifactType': 'container_image',
                          'Metadata': {'ImageID': args[-1]}, 'CreatedAt': '2026-11-09',
                          'Results': [{'Class': 'os-pkgs', 'Vulnerabilities': [{'Severity': 'HIGH'}]}]}
                Path(args[args.index('--output') + 1]).write_text(json.dumps(report))
            verification.run.side_effect = run
            with self.assertRaisesRegex(AssertionError, 'High/Critical'):
                verifier.scans(verification, '/trivy', {'sha256:first': ['redis'], 'sha256:second': ['kafka']})
            summary = json.loads((verification.output / 'scan-summary.json').read_text())
            self.assertEqual(len(summary['images']), 2)
            for call in verification.run.call_args_list:
                args = call.args[0]
                if 'image' in args:
                    self.assertEqual(args[args.index('--ignorefile') + 1], os.devnull)
                    self.assertEqual(args[args.index('--config') + 1], os.devnull)
                    self.assertIn('--ignore-unfixed=false', args)
                    self.assertEqual(args[args.index('--severity') + 1], 'UNKNOWN,LOW,MEDIUM,HIGH,CRITICAL')
            verification.record.assert_not_called()

    def test_unhealthy_service_waits_and_dead_service_fails(self):
        verification = Mock()
        state = {'Status': 'running', 'Health': {'Status': 'starting'}}
        verification.endpoints.return_value = ({}, {'fleet-api': {'State': state}})
        self.assertIsNone(verifier.ready(verification))
        state['Status'] = 'dead'
        with self.assertRaisesRegex(AssertionError, 'Service failed'):
            verifier.ready(verification)


if __name__ == '__main__':
    unittest.main()
