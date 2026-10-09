#!/usr/bin/env python3
"""FP-049: verifica runbook in progetto Compose isolato e sacrificabile."""
import argparse
import datetime as dt
import json
from pathlib import Path
import socket
import subprocess
import sys
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'load'))
from run import APPS, Stack, ack, command, frame, http
from diagnose import Inspector


def wait(check, description, timeout=40):
    until = time.monotonic() + timeout
    while True:
        try:
            result = check()
            if result:
                return result
        except (OSError, RuntimeError):
            pass
        if time.monotonic() >= until:
            raise AssertionError(description)
        time.sleep(.5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    stack = Stack(output)
    stack.project = 'fp049-' + uuid.uuid4().hex[:10]
    stack.base[3] = stack.project
    checks = []

    def record(name, evidence):
        checks.append(dict(check=name, evidence=evidence))
        (output / 'checks.json').write_text(json.dumps(checks, indent=2))

    def send(message):
        with socket.create_connection((stack.gateway[0], int(stack.gateway[1])), timeout=5) as stream:
            stream.sendall(frame(message))
            ack(stream, message)

    def count(message):
        return stack.sql(f"SELECT count(*) FROM telemetry_samples WHERE message_id='{message}'")

    def ready():
        for app in APPS:
            stack.urls[app] = 'http://' + stack.call('port', app, '8080').strip()
        stack.gateway = stack.call('port', 'telemetry-gateway', '7000').strip().rsplit(':', 1)
        try:
            return all(json.loads(http(url + '/actuator/health/readiness'))['status'] == 'UP'
                       for url in stack.urls.values())
        except OSError:
            return False

    def volume_names():
        raw = command(['docker', 'volume', 'ls', '--filter',
                       'label=com.docker.compose.project=' + stack.project, '--format', '{{.Name}}'])
        return sorted(raw.splitlines())

    try:
        print('Building isolated stack ' + stack.project, flush=True)
        stack.prepare()
        inspector = Inspector(stack.base)
        record('health nominal', inspector.health())
        record('status', inspector.call('ps', '--all'))
        suffix = uuid.uuid4().hex[:8]
        vehicle = json.loads(http(stack.urls['fleet-api'] + '/api/v1/vehicles',
            dict(externalCode='FP049-' + suffix, plate='O' + suffix,
                 serviceIntervalKm=15000, nextServiceAtKm=25000)))['id']
        message = dict(protocolVersion=1, messageId=str(uuid.uuid4()), vehicleId=vehicle,
            sequenceNumber=0, observedAt=dt.datetime.now(dt.timezone.utc).isoformat(),
            speedKmh=60, engineTemperatureC=150, batteryVoltage=12.6,
            odometerKm=10000, latitude=41.9, longitude=12.5)
        send(message)
        wait(lambda: count(message['messageId']) == '1', 'Nominal sample missing')
        wait(lambda: stack.sql(f"SELECT count(*) FROM maintenance_alerts WHERE source_message_id='{message['messageId']}'") == '1', 'Alert missing')
        record('sample and alert diagnosis', inspector.database(message['messageId']))
        def correlated_logs():
            data = inspector.logs(message['messageId'], '30m', 2000)
            return data if any(e.get('event.action') == 'telemetry.event.persisted'
                               for e in data['events']) else False

        logs = wait(correlated_logs, 'Persisted ECS log missing')
        assert any(e.get('event.action') == 'telemetry.publication.confirmed' for e in logs['events'])
        record('ECS correlation', logs)
        send(message)
        wait(lambda: any(e.get('event.action') == 'duplicate.telemetry.aggregate.ignored'
            for e in inspector.logs(message['messageId'], '30m', 2000)['events']), 'Duplicate log missing')
        assert count(message['messageId']) == '1'
        record('duplicate idempotent', inspector.database(message['messageId']))
        record('redis key', inspector.redis(vehicle))
        unknown = {**message, 'messageId': str(uuid.uuid4()), 'vehicleId': str(uuid.uuid4())}
        send(unknown)
        wait(lambda: stack.offsets()['KAFKA_TOPIC_REJECTED'] == 1, 'Rejection missing')
        rejected = []
        for partition in range(3):
            rows = inspector.records('rejected', partition, 0, 1)
            rejected.extend(rows['records'])
        assert any(row.get('messageId') == unknown['messageId'] for row in rejected)
        assert count(unknown['messageId']) == '0'
        record('rejection diagnosis', rejected)
        # Producer used only for test fixture: malformed JSON has no recoverable UUID.
        producer = [*stack.base, 'exec', '-T', 'kafka', '/opt/kafka/bin/kafka-console-producer.sh',
                    '--bootstrap-server', 'kafka:19092', '--topic', stack.env['KAFKA_TOPIC_RAW']]
        result = subprocess.run(producer, input='{broken-json\n', text=True, capture_output=True, timeout=30)
        assert result.returncode == 0
        wait(lambda: stack.offsets()['KAFKA_TOPIC_DEAD_LETTER'] == 1, 'DLT missing')
        terminal = []
        for partition in range(3):
            terminal.extend(inspector.records('dlt', partition, 0, 1)['records'])
        assert any('sourceOffset' in row and row.get('originalMessageId') is None for row in terminal)
        record('DLT without messageId', terminal)
        record('raw inspection', inspector.records('raw', 0, 0, 10))
        before = stack.kafka('kafka-consumer-groups.sh', '--describe', '--group', stack.env['KAFKA_CONSUMER_GROUP_ID'])
        for kind in ('raw', 'rejected', 'dlt'):
            inspector.records(kind, 0, 0, 10)
        after = stack.kafka('kafka-consumer-groups.sh', '--describe', '--group', stack.env['KAFKA_CONSUMER_GROUP_ID'])
        assert before == after
        record('inspection preserves group offsets', True)
        print('Testing Redis fallback, restart and retained volumes', flush=True)
        stack.call('stop', 'redis')
        newer = {**message, 'messageId': str(uuid.uuid4()), 'sequenceNumber': 1,
                 'observedAt': dt.datetime.now(dt.timezone.utc).isoformat()}
        send(newer)
        wait(lambda: count(newer['messageId']) == '1', 'Redis-down sample missing')
        state = json.loads(http(stack.urls['fleet-api'] + '/api/v1/vehicles/' + vehicle + '/state'))
        assert state['lastSequenceNumber'] == 1
        record('Redis down fallback', dict(sequence=state['lastSequenceNumber'], health=inspector.health()))
        stack.call('start', 'redis')
        wait(lambda: inspector.redis(vehicle)['PING'] == 'PONG', 'Redis not reachable')
        wait(lambda: json.loads(http(stack.urls['fleet-api'] + '/api/v1/vehicles/' + vehicle + '/state'))['lastSequenceNumber'] == 1,
             'State recovery failed')
        def repaired_cache():
            data = inspector.redis(vehicle)
            return data if data['GET'] else False

        repaired = wait(repaired_cache, 'Read repair missing')
        assert int(repaired['TTL']) > 0
        record('Redis read repair', repaired)
        stack.call('restart', 'telemetry-processor')
        wait(ready, 'Restart not ready', 120)
        assert count(message['messageId']) == '1'
        record('processor restart', inspector.health())
        old_id = stack.call('ps', '-q', 'telemetry-processor').strip()
        config = json.loads(stack.config.read_text())
        config['services']['telemetry-processor']['environment']['KAFKA_HEALTH_TIMEOUT'] = '1200ms'
        stack.config.write_text(json.dumps(config))
        stack.call('up', '-d', '--no-deps', 'telemetry-processor', timeout=120)
        wait(ready, 'Environment recreation not ready', 120)
        new_id = stack.call('ps', '-q', 'telemetry-processor').strip()
        assert old_id != new_id
        actual = stack.call('exec', '-T', 'telemetry-processor', 'printenv', 'KAFKA_HEALTH_TIMEOUT').strip()
        assert actual == '1200ms' and count(message['messageId']) == '1'
        record('environment update recreates container', dict(containerChanged=True, kafkaHealthTimeout=actual))
        retained = volume_names()
        stack.call('down', timeout=90)
        assert volume_names() == retained and retained
        stack.call('up', '-d', *APPS, timeout=120)
        wait(ready, 'Down/up not ready', 120)
        assert count(message['messageId']) == '1' and count(newer['messageId']) == '1'
        assert stack.offsets()['KAFKA_TOPIC_DEAD_LETTER'] == 1
        record('down/up preserves PostgreSQL and Kafka volumes', dict(volumes=retained, health=inspector.health()))
        # Exercise the documented entry point, including config resolution and metadata tools.
        for operation in ('status', 'health', 'kafka'):
            text = command(['python3', str(Path(__file__).with_name('diagnose.py')), '--project',
                            stack.project, '--file', str(stack.config), operation], timeout=120)
            record('CLI ' + operation, text)
        for operation in (('database', message['messageId']), ('redis', vehicle),
                          ('logs', message['messageId']),
                          ('records', 'dlt', '--partition', '0', '--offset', '0', '--count', '1')):
            text = command(['python3', str(Path(__file__).with_name('diagnose.py')), '--project',
                            stack.project, '--file', str(stack.config), *operation], timeout=30)
            record('CLI ' + operation[0], text)
        (output / 'summary.json').write_text(json.dumps(dict(result='passed', checks=len(checks), project=stack.project), indent=2))
    except BaseException as error:
        (output / 'failure.json').write_text(json.dumps(dict(error=str(error))))
        raise
    finally:
        stack.close()
        remaining = volume_names()
        if remaining:
            raise AssertionError('Isolated volumes not cleaned')
        (output / 'cleanup.json').write_text(json.dumps(dict(project=stack.project, remainingVolumes=remaining)))
    print(f'Passed {len(checks)} checks; isolated resources removed', flush=True)


if __name__ == '__main__':
    main()
