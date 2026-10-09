"""Regressioni dei falsi positivi nei failure scenarios FP-052."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch

import verify_failures
from verify_failures import reconcile, require_upstream_rejection


class FailureContractTest(unittest.TestCase):
    def setUp(self):
        self.message = dict(messageId='10000000-0000-0000-0000-000000000001')
        self.body = dict(protocolVersion=1, messageId=self.message['messageId'],
                         status='REJECTED', errorCode='UPSTREAM_UNAVAILABLE')

    def test_correlated_upstream_rejection(self):
        require_upstream_rejection(self.body, self.message)

    def test_false_accepted_during_outage_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'UPSTREAM_UNAVAILABLE'):
            require_upstream_rejection({**self.body, 'status': 'ACCEPTED'}, self.message)

    def test_rejection_for_different_message_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'correlated'):
            require_upstream_rejection({**self.body, 'messageId': 'different'}, self.message)

    def test_both_definitive_failure_and_late_delivery_are_valid(self):
        reconcile(raw=6, samples=6, duplicates=0)
        reconcile(raw=7, samples=6, duplicates=1)

    def test_unprocessed_duplicate_is_not_a_success(self):
        with self.assertRaisesRegex(AssertionError, 'reconciliation'):
            reconcile(raw=7, samples=6, duplicates=0)

    def test_duplicate_sample_is_not_a_success(self):
        with self.assertRaisesRegex(AssertionError, 'reconciliation'):
            reconcile(raw=7, samples=7, duplicates=0)

    def test_failure_cleans_stack_without_success_summary(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / 'evidence'
            stack = MagicMock()
            stack.base = ['docker', 'compose', '-p', 'placeholder']
            with patch('verify_failures.Stack', return_value=stack), \
                 patch('verify_failures.Failures.run', side_effect=AssertionError('backlog not drained')), \
                 patch('verify_failures.command', return_value=''), \
                 patch('sys.argv', ['verify_failures.py', '--output', str(output)]):
                with self.assertRaisesRegex(AssertionError, 'backlog not drained'):
                    verify_failures.main()
            stack.close.assert_called_once()
            self.assertFalse((output / 'summary.json').exists())
            self.assertEqual(json.loads((output / 'cleanup.json').read_text()),
                             dict(container=[], network=[], volume=[]))


if __name__ == '__main__':
    unittest.main()
