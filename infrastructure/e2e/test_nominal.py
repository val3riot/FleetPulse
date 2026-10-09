"""Regressioni per evitare false accettazioni del contratto latest-state."""
import unittest
import json
from pathlib import Path
import tempfile
from unittest.mock import MagicMock, patch
import verify_nominal
from verify_nominal import compare_state, expected_state, instant


class StateContractTest(unittest.TestCase):
    def setUp(self):
        self.message = dict(vehicleId='10000000-0000-0000-0000-000000000001', sequenceNumber=1,
            observedAt='2026-10-09T12:00:00.123456Z', speedKmh=61.25, engineTemperatureC=90,
            batteryVoltage=12.6, odometerKm=10001, latitude=41.9, longitude=12.5)

    def test_equivalent_timestamp_formats_are_accepted(self):
        state = expected_state(self.message)
        state['lastSeenAt'] = '2026-10-09T14:00:00.123456+02:00'
        compare_state(state, self.message)
        self.assertEqual(instant(state['lastSeenAt']), instant(self.message['observedAt']))

    def test_wrong_telemetry_is_rejected_even_with_correct_sequence(self):
        state = expected_state(self.message)
        state['batteryVoltage'] = 0
        with self.assertRaisesRegex(AssertionError, 'batteryVoltage'):
            compare_state(state, self.message)

    def test_previous_projection_is_not_accepted(self):
        state = expected_state(self.message)
        state['lastSequenceNumber'] = 0
        with self.assertRaisesRegex(AssertionError, 'lastSequenceNumber'):
            compare_state(state, self.message)

    def test_wrong_vehicle_is_not_accepted(self):
        state = expected_state(self.message)
        state['vehicleId'] = 'another-vehicle'
        with self.assertRaisesRegex(AssertionError, 'vehicleId'):
            compare_state(state, self.message)

    def test_api_contract_requires_stale_but_redis_does_not_contain_it(self):
        state = expected_state(self.message)
        with self.assertRaisesRegex(AssertionError, 'fields'):
            compare_state(state, self.message, api=True)
        state['stale'] = False
        compare_state(state, self.message, api=True)
        with self.assertRaisesRegex(AssertionError, 'fields'):
            compare_state(state, self.message)

    def test_unexpected_technical_fields_are_rejected(self):
        state = expected_state(self.message)
        state['messageId'] = 'not-in-the-contract'
        with self.assertRaisesRegex(AssertionError, 'fields'):
            compare_state(state, self.message)


    def test_kafka_startup_lines_are_not_parsed_as_records(self):
        key = self.message['vehicleId']
        raw = 'WARN consumer startup\n' + key + '\t' + json.dumps({'messageId': 'test'}) + '\n'
        self.assertEqual(verify_nominal.kafka_record(raw, key), (key, {'messageId': 'test'}))

    def test_wrong_kafka_key_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'vehicle key'):
            verify_nominal.kafka_record('wrong-key\t{}', self.message['vehicleId'])

    def test_failure_runs_cleanup_without_success_summary(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / 'evidence'
            stack = MagicMock()
            stack.base = ['docker', 'compose', '-p', 'placeholder']
            with patch('verify_nominal.Stack', return_value=stack), \
                 patch('verify_nominal.Nominal.run', side_effect=AssertionError('projection missing')), \
                 patch('verify_nominal.command', return_value=''), \
                 patch('sys.argv', ['verify_nominal.py', '--output', str(output)]):
                with self.assertRaisesRegex(AssertionError, 'projection missing'):
                    verify_nominal.main()
            stack.close.assert_called_once()
            self.assertFalse((output / 'summary.json').exists())
            self.assertEqual(json.loads((output / 'failure.json').read_text())['error'], 'projection missing')
            self.assertEqual(json.loads((output / 'cleanup.json').read_text()),
                             dict(container=[], network=[], volume=[]))


if __name__ == '__main__':
    unittest.main()
