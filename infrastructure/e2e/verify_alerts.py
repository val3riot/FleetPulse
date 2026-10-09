#!/usr/bin/env python3
"""FP-051: alert E2E e replay TCP dello stesso messageId senza duplicazioni."""
import argparse
import datetime as dt
import json
from pathlib import Path
import socket
import urllib.request
import uuid

from verify_nominal import Nominal, Stack, UTC, ack, command, frame, instant, metrics, request, require, wait

ALERT_FIELDS = dict(id='id', vehicleId='vehicle_id', sourceMessageId='source_message_id',
    type='type', severity='severity', description='description', status='status',
    createdAt='created_at', acknowledgedAt='acknowledged_at', closedAt='closed_at')


def compare_alert(body, row):
    require(set(body) == set(ALERT_FIELDS), 'Unexpected alert response fields')
    for field, column in ALERT_FIELDS.items():
        expected = row[column]
        if field.endswith('At') and expected is not None:
            require(body[field] is not None and
                    abs((instant(body[field]) - instant(expected)).total_seconds()) <= .000001,
                    'Alert timestamp mismatch: ' + field)
        else:
            require(body[field] == expected, 'Alert mismatch: ' + field)


def require_unchanged(before, after):
    require(before == after, 'Replay changed persisted sample or alert')


class Alerts(Nominal):
    def snapshot(self, message):
        vehicle = str(uuid.UUID(message['vehicleId']))
        return dict(samples=self.sql(f"SELECT row_to_json(t) FROM telemetry_samples t WHERE vehicle_id='{vehicle}' ORDER BY id"),
                    alerts=self.sql(f"SELECT row_to_json(a) FROM maintenance_alerts a WHERE vehicle_id='{vehicle}' ORDER BY id"))

    def api_alert(self, message, row):
        api = self.stack.urls['fleet-api'] + '/api/v1'
        paths = ('/vehicles/' + message['vehicleId'] + '/alerts',
                 '/alerts?vehicleId=' + message['vehicleId'])
        for path in paths:
            code, _, page = request(api + path)
            require(code == 200 and page['totalElements'] == 1 and len(page['content']) == 1,
                    'Expected exactly one alert in REST collection')
            compare_alert(page['content'][0], row)
        code, _, body = request(api + '/alerts/' + str(uuid.UUID(row['id'])))
        require(code == 200, 'Alert detail unavailable')
        compare_alert(body, row)
        return body

    def replay(self, message, baseline, duplicate_number):
        previous = self.offsets()
        processor = self.stack.urls['telemetry-processor']
        before = metrics(processor)
        with socket.create_connection((self.stack.gateway[0], int(self.stack.gateway[1])), timeout=5) as stream:
            stream.settimeout(5)
            stream.sendall(frame(message))
            ack(stream, message)
        _, observed = self.kafka_event(message, previous)
        self.record('Replay accepted and published', observed)
        # Positive processing barrier: unchanged counts immediately after ACK are insufficient.
        wait(lambda: metrics(processor)['fleetpulse_processor_duplicates_total'] == duplicate_number,
             'Processor has not classified the replay as a duplicate')
        wait(lambda: self.stack.lag()['total'] == 0, 'Replay offset not committed')
        after = metrics(processor)
        require(after['fleetpulse_processor_duplicates_total'] - before['fleetpulse_processor_duplicates_total'] == 1,
                'Expected exactly one new duplicate classification')
        require(after['fleetpulse_processor_persisted_total'] == before['fleetpulse_processor_persisted_total'],
                'Replay unexpectedly counted as new persistence')
        current = self.snapshot(message)
        require_unchanged(baseline, current)
        self.record('Replay processed without SQL changes', dict(duplicates=duplicate_number, snapshot=current))
        body = self.api_alert(message, current['alerts'][0])
        self.record('REST alert unchanged after replay', body)

    def run(self):
        api = self.stack.urls['fleet-api']
        suffix = uuid.uuid4().hex[:8]
        registration = dict(externalCode='FP051-' + suffix, plate='A' + suffix,
                            serviceIntervalKm=15000, nextServiceAtKm=25000)
        code, _, vehicle = request(api + '/api/v1/vehicles', registration)
        require(code == 201 and vehicle['status'] == 'ACTIVE', 'Registration failed')
        vid = str(uuid.UUID(vehicle['id']))
        for key, value in registration.items():
            require(vehicle[key] == value, 'Registration mismatch: ' + key)
        self.record('REST registration', vehicle)
        maximum = float(self.stack.env['TELEMETRY_ALERT_MAXIMUM_ENGINE_TEMPERATURE_C'])
        minimum = float(self.stack.env['TELEMETRY_ALERT_MINIMUM_BATTERY_VOLTAGE'])
        message = dict(protocolVersion=1, messageId=str(uuid.uuid4()), vehicleId=vid,
            sequenceNumber=0, observedAt=dt.datetime.now(UTC).isoformat(), speedKmh=60,
            engineTemperatureC=maximum + 10, batteryVoltage=minimum + 1,
            odometerKm=10000, latitude=41.9, longitude=12.5)
        with socket.create_connection((self.stack.gateway[0], int(self.stack.gateway[1])), timeout=5) as stream:
            stream.settimeout(5)
            self.verify_message(stream, message)
        wait(lambda: len(self.snapshot(message)['alerts']) == 1, 'Alert missing')
        wait(lambda: self.stack.lag()['total'] == 0, 'Initial offset not committed')
        original = self.snapshot(message)
        require(len(original['samples']) == 1 and len(original['alerts']) == 1,
                'Expected one sample and one alert')
        row = original['alerts'][0]
        require(row['vehicle_id'] == vid and row['source_message_id'] == message['messageId'],
                'Alert source mismatch')
        require(row['type'] == 'ENGINE_TEMPERATURE_HIGH' and row['severity'] == 'HIGH'
                and row['description'] == 'Temperatura motore oltre soglia', 'Alert rule mismatch')
        require(row['status'] == 'OPEN' and row['acknowledged_at'] is None and row['closed_at'] is None,
                'Unexpected initial alert lifecycle')
        require(instant(row['created_at']) == instant(original['samples'][0]['processed_at']),
                'Sample/alert creation timestamp mismatch')
        body = self.api_alert(message, row)
        self.record('One OPEN alert persisted and exposed by REST', dict(sql=row, api=body))
        require(metrics(self.stack.urls['telemetry-processor'])['fleetpulse_processor_duplicates_total'] == 0,
                'Unexpected duplicate before replay')
        self.replay(message, original, 1)

        # Preserve operator work: a telemetry replay must not reopen an acknowledged alert.
        data = json.dumps({'status': 'ACKNOWLEDGED'}).encode()
        req = urllib.request.Request(api + '/api/v1/alerts/' + row['id'], data=data, method='PATCH',
                                     headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            require(response.status == 200, 'Acknowledge failed')
            acknowledged_body = json.load(response)
        acknowledged = self.snapshot(message)
        acknowledged_row = acknowledged['alerts'][0]
        require(acknowledged_row['id'] == row['id'] and acknowledged_row['status'] == 'ACKNOWLEDGED'
                and acknowledged_row['acknowledged_at'] is not None and acknowledged_row['closed_at'] is None,
                'Invalid acknowledged alert lifecycle')
        require(acknowledged_row['created_at'] == row['created_at'], 'Acknowledge changed createdAt')
        require(acknowledged['samples'] == original['samples'], 'Acknowledge changed telemetry sample')
        compare_alert(acknowledged_body, acknowledged_row)
        self.record('Operator acknowledgement via REST', dict(sql=acknowledged_row, api=acknowledged_body))
        self.replay(message, acknowledged, 2)
        offsets = self.stack.offsets()
        require(offsets['KAFKA_TOPIC_RAW'] == 3 and offsets['KAFKA_TOPIC_REJECTED'] == 0
                and offsets['KAFKA_TOPIC_DEAD_LETTER'] == 0, 'Unexpected Kafka reconciliation')
        final = metrics(self.stack.urls['telemetry-processor'])
        require(final['fleetpulse_processor_persisted_total'] == 1
                and final['fleetpulse_processor_duplicates_total'] == 2, 'Unexpected processor reconciliation')
        self.record('Final reconciliation', dict(samples=1, alerts=1, raw=3, duplicates=2,
                    rejected=0, dlt=0, lag=0, alertStatus='ACKNOWLEDGED'))
        (self.stack.output / 'summary.json').write_text(json.dumps(dict(result='passed',
            checks=len(self.evidence), publications=3, samples=1, alerts=1, duplicates=2,
            project=self.stack.project), indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    stack = Stack(output)
    stack.project = 'fp051-' + uuid.uuid4().hex[:10]
    stack.base[3] = stack.project
    try:
        print('Building isolated stack ' + stack.project, flush=True)
        stack.prepare()
        Alerts(stack).run()
    except BaseException as error:
        (output / 'failure.json').write_text(json.dumps(dict(errorType=type(error).__name__,
            error=str(error) if isinstance(error, AssertionError) else 'Operation failed; inspect last completed check')))
        raise
    finally:
        stack.close()
        remaining = {}
        for kind in ('container', 'network', 'volume'):
            remaining[kind] = command(['docker', kind, 'ls',
                *(['-a'] if kind == 'container' else []), '-q', '--filter',
                'label=com.docker.compose.project=' + stack.project]).splitlines()
        (output / 'cleanup.json').write_text(json.dumps(remaining, indent=2))
        require(not any(remaining.values()), 'Isolated resources remain after cleanup')
    print('Passed: alert created once, 2 replays processed without duplicates; isolated resources removed', flush=True)


if __name__ == '__main__':
    main()
