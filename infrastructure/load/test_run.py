"""Protocol boundaries exercised without Docker or network listeners."""
import socket
import struct
import threading
import unittest

from run import ack, frame, percentile, flatten


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

    def test_nearest_rank_percentile(self):
        self.assertEqual(95, percentile(list(range(100, 0, -1)), .95))
        self.assertIsNone(percentile([], .95))


if __name__ == '__main__':
    unittest.main()
