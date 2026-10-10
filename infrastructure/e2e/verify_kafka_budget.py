#!/usr/bin/env python3
"""FP-056: NACK bounded, producer warm/cold, recovery e replay aggregato idempotente."""
import argparse
import json
from pathlib import Path
import socket
import time
import uuid
import urllib.error

from verify_failures import Failures, read_ack, require_bounded_rejection
from verify_nominal import Stack, ack, command, frame, metrics, request, require, wait


class KafkaBudget(Failures):
    def send(self, message):
        with socket.create_connection((self.stack.gateway[0], int(self.stack.gateway[1])), timeout=5) as stream:
            stream.settimeout(7)
            stream.sendall(frame(message))
            ack(stream, message)

    def aggregate(self, message):
        mid = str(uuid.UUID(message['messageId']))
        return dict(samples=self.rows(message), alerts=self.sql(
            f"SELECT row_to_json(t) FROM maintenance_alerts t WHERE source_message_id='{mid}' ORDER BY id"))

    def outage(self, sequence, cold):
        label = 'cold' if cold else 'warm'
        print('Testing ' + label + ' producer outage', flush=True)
        before = self.starts()
        self.stack.call('pause' if cold else 'stop', 'kafka')
        if cold:
            # Fixture: a fresh gateway JVM has no producer metadata. Recovery below never restarts it.
            self.stack.call('stop', 'telemetry-gateway')
            command(['docker', 'start', self.stack.ids['telemetry-gateway']])
            kafka_id = self.stack.ids['kafka']
            kafka_state = json.loads(command(['docker', 'inspect', kafka_id]))[0]['State']
            require(kafka_state['Paused'], 'Cold fixture unexpectedly resumed Kafka')
            self.stack.urls['telemetry-gateway'] = 'http://' + self.stack.call('port', 'telemetry-gateway', '8080').strip()
            self.stack.gateway = self.stack.call('port', 'telemetry-gateway', '7000').strip().rsplit(':', 1)
            self.probe('telemetry-gateway', 'liveness', 200, 120)
            def cold_not_ready():
                try:
                    status, _, body = request(self.stack.urls['telemetry-gateway'] + '/actuator/health/readiness')
                except urllib.error.HTTPError as error:
                    status, body = error.code, json.load(error)
                return dict(http=status, body=body) if status == 503 and body.get('status') in ('DOWN', 'OUT_OF_SERVICE') else False
            observed = wait(cold_not_ready, 'Cold gateway must remain not ready', timeout=120)
            self.record('Cold fixture readiness', observed)
            def tcp_available():
                try:
                    with socket.create_connection((self.stack.gateway[0], int(self.stack.gateway[1])), timeout=1):
                        return True
                except OSError:
                    return False
            wait(tcp_available, 'Cold gateway TCP listener unavailable', timeout=120)
        for app in ('telemetry-gateway', 'telemetry-processor'):
            if not (cold and app == 'telemetry-gateway'):
                self.probe(app, 'readiness', 503)
            self.probe(app, 'liveness', 200)
        starts = self.starts()
        require(all(starts[a] == before[a] for a in ('fleet-api', 'telemetry-processor')),
                'Unexpected API/processor restart')
        require((starts['telemetry-gateway'] != before['telemetry-gateway']) == cold,
                'Unexpected warm/cold producer fixture')
        message = self.message(sequence)
        message['engineTemperatureC'] = self.maximum + 10
        started = time.monotonic()
        with socket.create_connection((self.stack.gateway[0], int(self.stack.gateway[1])), timeout=5) as stream:
            stream.settimeout(7)
            stream.sendall(frame(message))
            body = read_ack(stream)
        elapsed = time.monotonic() - started
        require_bounded_rejection(body, message, elapsed)
        require(self.aggregate(message) == dict(samples=[], alerts=[]), 'Outage caused SQL effects')
        self.record(label + ' correlated bounded NACK', dict(ack=body, durationMs=elapsed * 1000,
                    decisionBudgetMs=5000, transportToleranceMs=1000))
        self.stack.call('unpause' if cold else 'start', 'kafka')
        for app in ('telemetry-gateway', 'telemetry-processor'):
            self.probe(app, 'readiness', 200, 120)
        self.send(message)
        def persisted():
            rows = self.aggregate(message)
            return rows if len(rows['samples']) == 1 and len(rows['alerts']) == 1 else False
        original = wait(persisted, 'Recovered aggregate missing')
        alert = original['alerts'][0]
        require(alert['type'] == 'ENGINE_TEMPERATURE_HIGH' and alert['severity'] == 'HIGH'
                and alert['status'] == 'OPEN' and alert['source_message_id'] == message['messageId'],
                'Unexpected generated alert')
        self.projection(message)
        self.state(message)
        wait(lambda: self.stack.lag()['total'] == 0, 'Recovery lag not drained')
        duplicate_before = metrics(self.stack.urls['telemetry-processor'])['fleetpulse_processor_duplicates_total']
        self.send(message)
        wait(lambda: metrics(self.stack.urls['telemetry-processor'])['fleetpulse_processor_duplicates_total']
             == duplicate_before + 1, 'Explicit replay not processed')
        wait(lambda: self.stack.lag()['total'] == 0, 'Replay lag not drained')
        require(self.aggregate(message) == original, 'Replay changed sample/alert IDs or timestamps')
        require(self.starts() == starts, 'Recovery required an application restart')
        self.record(label + ' recovery and replay', dict(messageId=message['messageId'],
                    sampleId=original['samples'][0]['id'], alertId=alert['id'],
                    sampleCount=1, alertCount=1, unchangedAfterReplay=True, appRestartsDuringRecovery=0))

    def run(self):
        suffix = uuid.uuid4().hex[:8]
        code, _, vehicle = request(self.stack.urls['fleet-api'] + '/api/v1/vehicles',
            dict(externalCode='FP056-' + suffix, plate='B' + suffix, serviceIntervalKm=15000, nextServiceAtKm=25000))
        require(code == 201, 'Registration failed')
        self.vehicle = str(uuid.UUID(vehicle['id']))
        self.maximum = float(self.stack.env['TELEMETRY_ALERT_MAXIMUM_ENGINE_TEMPERATURE_C'])
        self.minimum = float(self.stack.env['TELEMETRY_ALERT_MINIMUM_BATTERY_VOLTAGE'])
        self.nominal(self.message(0))
        self.outage(1, cold=False)
        self.outage(2, cold=True)
        counts = self.sql("SELECT json_build_object('samples', (SELECT count(*) FROM telemetry_samples), 'alerts', (SELECT count(*) FROM maintenance_alerts))")[0]
        offsets = self.stack.offsets()
        duplicates = metrics(self.stack.urls['telemetry-processor'])['fleetpulse_processor_duplicates_total']
        raw = offsets['KAFKA_TOPIC_RAW']
        require(counts == dict(samples=3, alerts=2) and 5 <= raw <= 7 and duplicates == raw - 3,
                'Unexpected raw/sample/alert/duplicate reconciliation')
        require(offsets['KAFKA_TOPIC_REJECTED'] == 0 and offsets['KAFKA_TOPIC_DEAD_LETTER'] == 0,
                'Unexpected terminal records')
        self.record('Final reconciliation', dict(sql=counts, raw=raw, duplicates=duplicates, lag=0))
        (self.stack.output / 'summary.json').write_text(json.dumps(dict(result='passed',
            project=self.stack.project, checks=len(self.evidence), samples=3, alerts=2,
            raw=raw, duplicates=duplicates, coldFixtureGatewayStarts=1), indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    output = parser.parse_args().output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    stack = Stack(output)
    stack.project = 'fp056-' + uuid.uuid4().hex[:10]
    stack.base[3] = stack.project
    try:
        print('Building isolated stack ' + stack.project, flush=True)
        stack.prepare()
        environment = json.loads(stack.config.read_text())['services']['telemetry-gateway']['environment']
        expected = dict(KAFKA_CONFIRMATION_TIMEOUT='5s', GATEWAY_KAFKA_MAX_BLOCK_MS='1000',
                        GATEWAY_KAFKA_REQUEST_TIMEOUT_MS='1000', GATEWAY_KAFKA_DELIVERY_TIMEOUT_MS='4000',
                        GATEWAY_KAFKA_LINGER_MS='0')
        require(all(str(environment.get(key)) == value for key, value in expected.items()),
                'FP-056 verifier requires the documented 5s baseline producer settings')
        KafkaBudget(stack).run()
    except BaseException as error:
        (output / 'failure.json').write_text(json.dumps(dict(errorType=type(error).__name__,
            error=str(error) if isinstance(error, AssertionError) else 'Operation failed')))
        raise
    finally:
        # A failed cold fixture may leave the broker paused; release it before Compose stop.
        try:
            if 'kafka' in stack.ids:
                state = json.loads(command(['docker', 'inspect', stack.ids['kafka']]))[0]['State']
                if state.get('Paused'):
                    stack.call('unpause', 'kafka')
        finally:
            stack.close()
        remaining = {kind: command(['docker', kind, 'ls', *(['-a'] if kind == 'container' else []),
            '-q', '--filter', 'label=com.docker.compose.project=' + stack.project]).splitlines()
            for kind in ('container', 'network', 'volume')}
        (output / 'cleanup.json').write_text(json.dumps(remaining, indent=2))
        require(not any(remaining.values()), 'Isolated resources remain')
    print('Passed: warm/cold bounded NACK, recovery and aggregate replay; resources removed', flush=True)


if __name__ == '__main__':
    main()
