#!/usr/bin/env python3
"""FP-052: processor down, Redis down, frame invalido e Kafka down/recovery."""
import argparse
import datetime as dt
import json
from pathlib import Path
import socket
import struct
import time
import urllib.error
import uuid

from verify_nominal import Nominal, Stack, UTC, ack, command, compare_state, frame, metrics, request, require, wait
from run import APPS, read_exact


def read_ack(stream):
    size = struct.unpack('!I', read_exact(stream, 4))[0]
    require(0 < size <= 65536, 'Invalid acknowledgement length')
    return json.loads(read_exact(stream, size))


def require_upstream_rejection(body, message):
    require(body.get('protocolVersion') == 1 and body.get('messageId') == message['messageId']
            and body.get('status') == 'REJECTED' and body.get('errorCode') == 'UPSTREAM_UNAVAILABLE',
            'Expected correlated UPSTREAM_UNAVAILABLE, never ACCEPTED while Kafka is down')


def reconcile(raw, samples, duplicates):
    require(raw in (6, 7) and samples == 6 and duplicates == raw - samples,
            'Unexpected final raw/sample/duplicate reconciliation')


class Failures(Nominal):
    def message(self, sequence):
        return dict(protocolVersion=1, messageId=str(uuid.uuid4()), vehicleId=self.vehicle,
            sequenceNumber=sequence, observedAt=dt.datetime.now(UTC).isoformat(),
            speedKmh=60 + sequence, engineTemperatureC=self.maximum - 2,
            batteryVoltage=self.minimum + 1, odometerKm=10000 + sequence, latitude=41.9, longitude=12.5)

    def send(self, message):
        with socket.create_connection((self.stack.gateway[0], int(self.stack.gateway[1])), timeout=5) as stream:
            stream.settimeout(10)
            stream.sendall(frame(message))
            ack(stream, message)

    def rows(self, message):
        mid = str(uuid.UUID(message['messageId']))
        return self.sql(f"SELECT row_to_json(t) FROM telemetry_samples t WHERE message_id='{mid}'")

    def state(self, message):
        code, _, body = request(self.stack.urls['fleet-api'] + '/api/v1/vehicles/' + self.vehicle + '/state')
        require(code == 200 and type(body['stale']) is bool, 'State API unavailable')
        compare_state(body, message, api=True)
        return body

    def projection(self, message):
        def current():
            raw = self.redis(self.vehicle)
            if not raw:
                return False
            body = json.loads(raw)
            if body.get('lastSequenceNumber') != message['sequenceNumber']:
                return False
            compare_state(body, message)
            return body
        body = wait(current, 'Latest Redis projection missing')
        require(0 < int(self.redis(self.vehicle, 'TTL')) <= 300, 'Invalid Redis TTL')
        return body

    def probe(self, app, group, code, timeout=30):
        def matches():
            try:
                actual, _, body = request(self.stack.urls[app] + '/actuator/health/' + group)
            except urllib.error.HTTPError as error:
                actual, body = error.code, json.load(error)
            except OSError:
                return False
            return actual == code and body.get('status') == ('UP' if code == 200 else 'DOWN')
        wait(matches, app + '/' + group + ' did not reach expected status', timeout)

    def starts(self):
        return {app: metrics(url)['process_start_time_seconds'] for app, url in self.stack.urls.items()}

    def nominal(self, message):
        with socket.create_connection((self.stack.gateway[0], int(self.stack.gateway[1])), timeout=5) as stream:
            stream.settimeout(10)
            self.verify_message(stream, message)
        wait(lambda: self.stack.lag()['total'] == 0, 'Nominal offset not committed')

    def processor_down(self, original):
        print('Testing stopped processor and retained Kafka backlog', flush=True)
        before = self.starts()
        self.stack.call('stop', 'telemetry-processor')
        message = self.message(1)
        previous = self.offsets()
        self.send(message)
        _, event = self.kafka_event(message, previous)
        require(not self.rows(message), 'Stopped processor persisted new telemetry')
        compare_state(json.loads(self.redis(self.vehicle)), original)
        self.state(original)
        require(self.stack.lag()['total'] == 1, 'Expected one pending Kafka record')
        self.record('Processor down: ACK/raw/backlog, SQL and state unchanged', event)
        offsets = self.offsets()
        self.stack.call('start', 'telemetry-processor')
        self.stack.urls['telemetry-processor'] = 'http://' + self.stack.call('port', 'telemetry-processor', '8080').strip()
        self.probe('telemetry-processor', 'readiness', 200, 120)
        wait(lambda: len(self.rows(message)) == 1, 'Backlog was not persisted after restart')
        self.projection(message)
        self.state(message)
        wait(lambda: self.stack.lag()['total'] == 0, 'Recovered processor did not drain backlog')
        require(self.offsets() == offsets, 'Recovery unexpectedly required republishing')
        after = self.starts()
        require(after['telemetry-processor'] != before['telemetry-processor'] and
                all(after[app] == before[app] for app in ('fleet-api', 'telemetry-gateway')),
                'Unexpected application restart during processor recovery')
        self.record('Processor recovery without republish', dict(messageId=message['messageId'], lag=0))

    def redis_down(self):
        print('Testing Redis outage, fallback, read repair and new projection', flush=True)
        starts = self.starts()
        api = self.stack.urls['fleet-api']
        processor = self.stack.urls['telemetry-processor']
        self.stack.call('stop', 'redis')
        for app in APPS:
            self.probe(app, 'readiness', 200)
        message = self.message(2)
        before = metrics(processor)['fleetpulse_redis_update_failures_total']
        previous = self.offsets()
        self.send(message)
        _, observed = self.kafka_event(message, previous)
        wait(lambda: len(self.rows(message)) == 1, 'Redis outage blocked persistence')
        wait(lambda: metrics(processor)['fleetpulse_redis_update_failures_total'] > before,
             'Projection failure not observed')
        baseline = metrics(api)
        body = self.state(message)
        after = metrics(api)
        deltas = {name: after['fleetpulse_api_cache_' + name + '_total'] - baseline['fleetpulse_api_cache_' + name + '_total']
                  for name in ('fallback', 'failures', 'repair_failures')}
        require(all(value == 1 for value in deltas.values()), 'Expected fallback, cache failure and repair failure')
        self.record('Redis down: SQL committed and API fallback succeeded', dict(kafka=observed, state=body, deltas=deltas))
        wait(lambda: self.stack.lag()['total'] == 0, 'Redis outage blocked offset commit')
        self.stack.call('start', 'redis')
        wait(lambda: self.stack.call('exec', '-T', 'redis', 'redis-cli', 'ping').strip() == 'PONG', 'Redis not reachable')
        require(not self.redis(self.vehicle), 'Restarted nonpersistent Redis should have empty cache')
        baseline = metrics(api)
        self.state(message)
        repaired = self.projection(message)
        self.state(message)
        after = metrics(api)
        require(after['fleetpulse_api_cache_fallback_total'] - baseline['fleetpulse_api_cache_fallback_total'] == 1
                and after['fleetpulse_api_cache_hits_total'] - baseline['fleetpulse_api_cache_hits_total'] == 1,
                'Expected read repair followed by cache hit')
        require(self.starts() == starts, 'Application restarted during Redis recovery')
        self.record('Redis recovery: read repair then cache hit without app restart', repaired)
        self.nominal(self.message(3))
        require(self.starts() == starts, 'Processor restarted to resume projection')
        self.record('New telemetry updates Redis after recovery', dict(sequence=3))

    def invalid_frame(self):
        print('Testing invalid length rejection and healthy gateway', flush=True)
        raw = self.offsets()
        rows = self.sql('SELECT row_to_json(t) FROM telemetry_samples t ORDER BY id')
        projection = self.redis(self.vehicle)
        gateway = self.stack.urls['telemetry-gateway']
        metric = 'fleetpulse_gateway_frames_rejected_total{reason="invalid_length"}'
        before = metrics(gateway)[metric]
        with socket.create_connection((self.stack.gateway[0], int(self.stack.gateway[1])), timeout=5) as stream:
            stream.settimeout(5)
            stream.sendall(struct.pack('!I', 0))
            try:
                data = stream.recv(1)
            except ConnectionResetError:
                data = b''
            require(data == b'', 'Invalid frame must close without application ACK')
        wait(lambda: metrics(gateway)[metric] == before + 1, 'Invalid frame not counted')
        require(self.offsets() == raw and self.sql('SELECT row_to_json(t) FROM telemetry_samples t ORDER BY id') == rows
                and self.redis(self.vehicle) == projection, 'Invalid frame caused downstream effects')
        self.probe('telemetry-gateway', 'readiness', 200)
        self.record('Invalid frame closed and downstream unchanged', dict(reason='invalid_length', length=0))
        self.nominal(self.message(4))
        self.record('Gateway accepts valid telemetry after invalid frame', dict(sequence=4))

    def kafka_down(self):
        print('Testing Kafka outage, negative ACK and retry with same messageId', flush=True)
        starts = self.starts()
        self.stack.call('stop', 'kafka')
        for app in ('telemetry-gateway', 'telemetry-processor'):
            self.probe(app, 'readiness', 503)
            self.probe(app, 'liveness', 200)
        self.probe('fleet-api', 'readiness', 200)
        message = self.message(5)
        started = time.monotonic()
        with socket.create_connection((self.stack.gateway[0], int(self.stack.gateway[1])), timeout=5) as stream:
            # Producer metadata acquisition can consume max.block.ms before confirmation wait.
            stream.settimeout(75)
            stream.sendall(frame(message))
            body = read_ack(stream)
        require_upstream_rejection(body, message)
        require(not self.rows(message), 'Kafka-down attempt unexpectedly persisted before recovery')
        self.record('Kafka down: correlated negative ACK and no false ACCEPTED',
                    dict(ack=body, durationMs=(time.monotonic() - started) * 1000))
        self.stack.call('start', 'kafka')
        for app in ('telemetry-gateway', 'telemetry-processor'):
            self.probe(app, 'readiness', 200, 120)
        # The timed-out send may already have arrived after broker recovery: retry is idempotent.
        self.send(message)
        wait(lambda: len(self.rows(message)) == 1, 'Retry not persisted after Kafka recovery')
        self.projection(message)
        self.state(message)
        wait(lambda: self.stack.lag()['total'] == 0, 'Kafka recovery did not drain backlog')
        require(self.starts() == starts, 'Application restarted during Kafka recovery')
        self.record('Kafka recovery: same-message retry persisted once without app restart',
                    dict(messageId=message['messageId'], sample=self.rows(message)[0]))

    def run(self):
        suffix = uuid.uuid4().hex[:8]
        code, _, vehicle = request(self.stack.urls['fleet-api'] + '/api/v1/vehicles',
            dict(externalCode='FP052-' + suffix, plate='F' + suffix, serviceIntervalKm=15000, nextServiceAtKm=25000))
        require(code == 201 and vehicle['status'] == 'ACTIVE', 'Registration failed')
        self.vehicle = str(uuid.UUID(vehicle['id']))
        self.maximum = float(self.stack.env['TELEMETRY_ALERT_MAXIMUM_ENGINE_TEMPERATURE_C'])
        self.minimum = float(self.stack.env['TELEMETRY_ALERT_MINIMUM_BATTERY_VOLTAGE'])
        self.record('REST registration', vehicle)
        original = self.message(0)
        self.nominal(original)
        self.processor_down(original)
        self.redis_down()
        self.invalid_frame()
        self.kafka_down()
        counts = self.sql('SELECT json_build_object(\'samples\', (SELECT count(*) FROM telemetry_samples), \'alerts\', (SELECT count(*) FROM maintenance_alerts))')[0]
        offsets = self.stack.offsets()
        duplicates = metrics(self.stack.urls['telemetry-processor'])['fleetpulse_processor_duplicates_total']
        reconcile(offsets['KAFKA_TOPIC_RAW'], counts['samples'], duplicates)
        require(counts['alerts'] == 0 and offsets['KAFKA_TOPIC_REJECTED'] == 0
                and offsets['KAFKA_TOPIC_DEAD_LETTER'] == 0, 'Unexpected alert or terminal records')
        self.record('Final reconciliation', dict(sql=counts, kafka=offsets, duplicates=duplicates, lag=0))
        (self.stack.output / 'summary.json').write_text(json.dumps(dict(result='passed', checks=len(self.evidence),
            scenarios=4, samples=counts['samples'], raw=offsets['KAFKA_TOPIC_RAW'], duplicates=duplicates,
            project=self.stack.project), indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    stack = Stack(output)
    stack.project = 'fp052-' + uuid.uuid4().hex[:10]
    stack.base[3] = stack.project
    try:
        print('Building isolated stack ' + stack.project, flush=True)
        stack.prepare()
        Failures(stack).run()
    except BaseException as error:
        (output / 'failure.json').write_text(json.dumps(dict(errorType=type(error).__name__,
            error=str(error) if isinstance(error, AssertionError) else 'Operation failed; inspect last completed check')))
        raise
    finally:
        stack.close()
        remaining = {}
        for kind in ('container', 'network', 'volume'):
            remaining[kind] = command(['docker', kind, 'ls', *(['-a'] if kind == 'container' else []),
                '-q', '--filter', 'label=com.docker.compose.project=' + stack.project]).splitlines()
        (output / 'cleanup.json').write_text(json.dumps(remaining, indent=2))
        require(not any(remaining.values()), 'Isolated resources remain after cleanup')
    print('Passed: 4 failure scenarios; isolated resources removed', flush=True)


if __name__ == '__main__':
    main()
