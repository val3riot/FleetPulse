import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('ci_summary', Path(__file__).with_name('summary.py'))
summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summary)


class GateTest(unittest.TestCase):
    def successes(self):
        return {job: {'result': 'success'} for job in summary.REQUIRED}

    def test_success_requires_every_required_job(self):
        passed, text = summary.summarize(self.successes())
        self.assertTrue(passed)
        self.assertIn('**PASS**', text)
        self.assertIn('non è eseguita', text)

    def test_failure_cancelled_and_skipped_jobs_block_the_gate(self):
        for job in summary.REQUIRED:
            for status in ['failure', 'cancelled', 'skipped']:
                needs = self.successes()
                needs[job]['result'] = status
                with self.subTest(job=job, status=status):
                    passed, text = summary.summarize(needs)
                    self.assertFalse(passed)
                    self.assertIn('**FAIL**', text)

    def test_missing_and_unexpected_jobs_are_not_success(self):
        needs = self.successes()
        needs.pop('integration')
        with self.assertRaises(ValueError):
            summary.summarize(needs)
        needs = self.successes()
        needs['extra'] = {'result': 'success'}
        with self.assertRaises(ValueError):
            summary.summarize(needs)

    def test_invalid_payload_or_status_is_rejected(self):
        for needs in [None, [], {**self.successes(), 'backend': {}},
                      {**self.successes(), 'backend': {'result': 'unknown'}}]:
            with self.subTest(needs=needs), self.assertRaises(ValueError):
                summary.summarize(needs)

    def test_exit_code_and_github_summary_match_actual_jobs(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'summary.md'
            for status, expected in [('success', 0), ('failure', 1)]:
                needs = self.successes()
                needs['frontend']['result'] = status
                with patch.dict(os.environ, {'NEEDS_JSON': json.dumps(needs), 'GITHUB_STEP_SUMMARY': str(path)}), patch('builtins.print'):
                    self.assertEqual(summary.main(), expected)
            text = path.read_text()
            self.assertIn('**PASS**', text)
            self.assertIn('**FAIL**', text)

    def test_malformed_json_cannot_produce_a_successful_check(self):
        with patch.dict(os.environ, {'NEEDS_JSON': 'invalid'}, clear=True), patch('builtins.print'):
            self.assertEqual(summary.main(), 1)


if __name__ == '__main__':
    unittest.main()
