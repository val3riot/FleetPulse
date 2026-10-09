"""Regressioni dei confini di sicurezza e della correlazione diagnostica."""
import json
import subprocess
import unittest
from unittest.mock import patch

from diagnose import Inspector, identifier, run

MESSAGE = '10000000-0000-0000-0000-000000000001'


class DiagnoseTest(unittest.TestCase):
    def setUp(self):
        self.inspector = Inspector.__new__(Inspector)
        self.inspector.env = {'KAFKA_TOPIC_RAW': 'custom.raw', 'KAFKA_TOPIC_DEAD_LETTER': 'custom.dlt'}

    def test_sql_rejects_non_uuid_before_executing(self):
        with patch.object(self.inspector, 'call') as call:
            with self.assertRaises(ValueError):
                self.inspector.database("'; DROP TABLE vehicles;--")
            call.assert_not_called()

    def test_sql_session_is_read_only_with_deadline(self):
        with patch.object(self.inspector, 'call', return_value='rows') as call:
            self.inspector.database(MESSAGE)
            query = call.call_args.args[-1]
            self.assertIn('BEGIN READ ONLY', query)
            self.assertIn("statement_timeout='5s'", query)
            self.assertIn('maintenance_alerts', query)

    def test_nested_ecs_and_source_coordinates_without_payload(self):
        entry = {'event': {'action': 'kafka.telemetry.record.handled'}, 'messageId': MESSAGE,
                 'topic': 'custom.raw', 'partition': 2, 'offset': 8, 'message': 'private payload'}
        with patch.object(self.inspector, 'call', return_value=json.dumps(entry)):
            events = self.inspector.logs(MESSAGE, '5m', 100)['events']
            self.assertEqual(events[0]['event.action'], 'kafka.telemetry.record.handled')
            self.assertEqual(events[0]['offset'], 8)
            self.assertNotIn('message', events[0])

    def test_text_log_is_counted_without_exposure(self):
        with patch.object(self.inspector, 'call', return_value=MESSAGE + ' private payload'):
            result = self.inspector.logs(MESSAGE, '5m', 100)
            self.assertEqual(result['nonJsonMatches'], 1)
            self.assertNotIn('private payload', json.dumps(result))

    def test_dlt_coordinates_work_without_message_id_and_do_not_commit(self):
        event = dict(sourceTopic='custom.raw', sourcePartition=1, sourceOffset=4,
                     originalPayload={'malformed': 'private'}, errorCode='BAD_EVENT')
        with patch.object(self.inspector, 'kafka', return_value=json.dumps(event)) as kafka:
            result = self.inspector.records('dlt', 2, 3, 10)
            self.assertEqual(result['records'][0]['sourceOffset'], 4)
            self.assertIsNone(result['records'][0]['originalMessageId'])
            self.assertNotIn('private', json.dumps(result))
            self.assertIn('enable.auto.commit=false', kafka.call_args.args)
            self.assertNotIn('--group', kafka.call_args.args)
            self.assertIn('custom.dlt', kafka.call_args.args)

    def test_command_failure_does_not_echo_secrets(self):
        completed = subprocess.CompletedProcess([], 1, stdout='password=secret', stderr='secret')
        with patch('diagnose.subprocess.run', return_value=completed):
            with self.assertRaises(RuntimeError) as caught:
                run(['docker', 'compose', 'config'])
            self.assertNotIn('secret', str(caught.exception))


if __name__ == '__main__':
    unittest.main()
