"""Protocol boundaries exercised without Docker or network listeners."""
import socket
import struct
import threading
import unittest

from run import ack, frame, percentile, flatten, Stack
from pathlib import Path
from unittest.mock import patch


class LoadProtocolTest(unittest.TestCase):
    def test_fragmented_ack_is_correlated(self):
        client, server = socket.socketpair()
        message = {'messageId': '00000000-0000-0000-0000-000000000001'}
        response = frame(dict(protocolVersion=1, status='ACCEPTED', **message))
        def send():
            with server:
                for byte in response:
                    server.sendall(bytes([byte]))
        thread = threading.Thread(target=send)
        thread.start()
        with client:
            client.settimeout(1)
            ack(client, message)
        thread.join()

    def test_rejected_or_uncorrelated_ack_fails(self):
        for response in [dict(protocolVersion=1, status='REJECTED', messageId='a'),
                         dict(protocolVersion=1, status='ACCEPTED', messageId='b')]:
            client, server = socket.socketpair()
            with client, server:
                server.sendall(frame(response))
                with self.assertRaises(ValueError):
                    ack(client, {'messageId': 'a'})

    def test_truncated_and_oversized_ack_fail(self):
        for content, error in [(b'\x00\x01', EOFError),
                               (struct.pack('!I', 65537), ValueError)]:
            client, server = socket.socketpair()
            with client:
                server.sendall(content)
                server.close()
                with self.assertRaises(error):
                    ack(client, {'messageId': 'a'})

    def test_nested_ecs_fields_are_flattened(self):
        self.assertEqual({"pipeline.persistence.latency.ms": 12.5},
                         flatten({"pipeline": {"persistence": {"latency": {"ms": 12.5}}}}))

    def test_lag_handles_uncommitted_partitions_of_fresh_isolated_topic(self):
        stack = Stack(Path('/unused'))
        stack.env = {'KAFKA_TOPIC_RAW': 'raw', 'KAFKA_CONSUMER_GROUP_ID': 'group'}
        for current, end, reported, expected in [('-', '0', '-', 0),
                                                 ('-', '5', '-', 5), ('6', '9', '3', 3)]:
            with patch.object(stack, 'kafka', return_value=f'group raw 0 {current} {end} {reported} consumer host client'):
                self.assertEqual(expected, stack.lag()['total'])

    def test_nearest_rank_percentile(self):
        self.assertEqual(95, percentile(list(range(100, 0, -1)), .95))
        self.assertIsNone(percentile([], .95))


if __name__ == '__main__':
    unittest.main()
