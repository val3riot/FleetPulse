"""Reject false success and the legacy unbounded Kafka-outage behavior."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch

from verify_failures import require_bounded_rejection
import verify_kafka_budget


class KafkaBudgetContractTest(unittest.TestCase):
    def setUp(self):
        self.message = dict(messageId='10000000-0000-0000-0000-000000000001')
        self.nack = dict(protocolVersion=1, messageId=self.message['messageId'],
                         status='REJECTED', errorCode='UPSTREAM_UNAVAILABLE')

    def test_warm_and_cold_bounds_accept_correlated_nack(self):
        for seconds in (1, 4, 6):
            require_bounded_rejection(self.nack, self.message, seconds)

    def test_old_sixty_second_rejection_fails_budget(self):
        for seconds in (6.01, 60.23):
            with self.assertRaisesRegex(AssertionError, 'budget'):
                require_bounded_rejection(self.nack, self.message, seconds)

    def test_fast_false_accepted_is_not_a_success(self):
        with self.assertRaisesRegex(AssertionError, 'UPSTREAM_UNAVAILABLE'):
            require_bounded_rejection({**self.nack, 'status': 'ACCEPTED'}, self.message, .01)

    def test_failure_removes_stack_without_success_summary(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / 'evidence'
            stack = MagicMock()
            stack.base = ['docker', 'compose', '-p', 'placeholder']
            stack.config = output / 'compose.private.json'
            stack.ids = {'kafka': 'isolated-broker'}
            environment = dict(KAFKA_CONFIRMATION_TIMEOUT='5s', GATEWAY_KAFKA_MAX_BLOCK_MS='1000',
                GATEWAY_KAFKA_REQUEST_TIMEOUT_MS='1000', GATEWAY_KAFKA_DELIVERY_TIMEOUT_MS='4000',
                GATEWAY_KAFKA_LINGER_MS='0')
            stack.prepare.side_effect = lambda: stack.config.write_text(json.dumps(
                dict(services={'telemetry-gateway': dict(environment=environment)})))
            with patch('verify_kafka_budget.Stack', return_value=stack), \
                 patch('verify_kafka_budget.KafkaBudget.run', side_effect=AssertionError('budget exceeded')), \
                 patch('verify_kafka_budget.command', side_effect=lambda args:
                     json.dumps([dict(State=dict(Paused=True))]) if args[1] == 'inspect' else ''), \
                 patch('sys.argv', ['verify_kafka_budget.py', '--output', str(output)]):
                with self.assertRaisesRegex(AssertionError, 'budget exceeded'):
                    verify_kafka_budget.main()
            stack.call.assert_called_once_with('unpause', 'kafka')
            stack.close.assert_called_once()
            self.assertFalse((output / 'summary.json').exists())
            self.assertEqual(json.loads((output / 'cleanup.json').read_text()),
                             dict(container=[], network=[], volume=[]))


if __name__ == '__main__':
    unittest.main()
