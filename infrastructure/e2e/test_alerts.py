"""Regressioni dei controlli alert/idempotenza FP-051."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch

import verify_alerts
from verify_alerts import ALERT_FIELDS, compare_alert, require_unchanged


class AlertContractTest(unittest.TestCase):
    def setUp(self):
        self.row = dict(id='alert-1', vehicle_id='vehicle-1', source_message_id='message-1',
            type='ENGINE_TEMPERATURE_HIGH', severity='HIGH', description='Temperatura motore oltre soglia',
            status='OPEN', created_at='2026-10-09T12:00:00Z', acknowledged_at=None, closed_at=None)
        self.body = {field: self.row[column] for field, column in ALERT_FIELDS.items()}

    def test_correct_alert_and_equivalent_timestamp(self):
        self.body['createdAt'] = '2026-10-09T14:00:00+02:00'
        compare_alert(self.body, self.row)

    def test_wrong_source_is_rejected(self):
        self.body['sourceMessageId'] = 'another-message'
        with self.assertRaisesRegex(AssertionError, 'sourceMessageId'):
            compare_alert(self.body, self.row)

    def test_changed_identity_is_rejected_even_if_count_is_unchanged(self):
        before = dict(samples=[{'id': 1}], alerts=[self.row])
        after = dict(samples=[{'id': 1}], alerts=[{**self.row, 'id': 'replacement-alert'}])
        with self.assertRaisesRegex(AssertionError, 'Replay changed'):
            require_unchanged(before, after)

    def test_reopened_alert_is_rejected(self):
        before = dict(samples=[{'id': 1}], alerts=[{**self.row, 'status': 'ACKNOWLEDGED',
                                                  'acknowledged_at': '2026-10-09T12:01:00Z'}])
        after = dict(samples=[{'id': 1}], alerts=[self.row])
        with self.assertRaisesRegex(AssertionError, 'Replay changed'):
            require_unchanged(before, after)

    def test_duplicate_alert_is_rejected(self):
        before = dict(samples=[{'id': 1}], alerts=[self.row])
        after = dict(samples=[{'id': 1}], alerts=[self.row, {**self.row, 'id': 'alert-2'}])
        with self.assertRaisesRegex(AssertionError, 'Replay changed'):
            require_unchanged(before, after)

    def test_failure_triggers_cleanup_and_no_success_summary(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / 'evidence'
            stack = MagicMock()
            stack.base = ['docker', 'compose', '-p', 'placeholder']
            with patch('verify_alerts.Stack', return_value=stack), \
                 patch('verify_alerts.Alerts.run', side_effect=AssertionError('replay not consumed')), \
                 patch('verify_alerts.command', return_value=''), \
                 patch('sys.argv', ['verify_alerts.py', '--output', str(output)]):
                with self.assertRaisesRegex(AssertionError, 'replay not consumed'):
                    verify_alerts.main()
            stack.close.assert_called_once()
            self.assertFalse((output / 'summary.json').exists())
            self.assertEqual(json.loads((output / 'cleanup.json').read_text()),
                             dict(container=[], network=[], volume=[]))


if __name__ == '__main__':
    unittest.main()
