#!/usr/bin/env python3
"""FP-050: registrazione REST → TCP/ACK → Kafka → SQL → Redis → State API."""
import argparse
import datetime as dt
import json
from pathlib import Path
import socket
import sys
import time
import urllib.parse
import urllib.request
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'load'))
from run import APPS, Stack, ack, command, frame, metrics

FIELDS = ('speedKmh', 'engineTemperatureC', 'batteryVoltage', 'odometerKm', 'latitude', 'longitude')
SQL_FIELDS = dict(zip(FIELDS, ('speed_kmh', 'engine_temperature_c', 'battery_voltage',
                             'odometer_km', 'latitude', 'longitude')))
UTC = dt.timezone.utc


def instant(value):
    return dt.datetime.fromisoformat(value.replace('Z', '+00:00')).astimezone(UTC)


def require(condition, description):
    if not condition:
        raise AssertionError(description)


def request(url, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=5) as response:
        return response.status, dict(response.headers), json.load(response)


def wait(check, description, timeout=30):
    until = time.monotonic() + timeout
    while True:
        result = check()
        if result:
            return result
        if time.monotonic() >= until:
            raise AssertionError(description)
        time.sleep(.2)


def expected_state(message):
    return dict(vehicleId=message['vehicleId'], lastSequenceNumber=message['sequenceNumber'],
                lastSeenAt=message['observedAt'], **{key: message[key] for key in FIELDS})


def compare_state(actual, message, api=False):
    expected = expected_state(message)
    require(set(actual) == set(expected) | ({'stale'} if api else set()), 'Unexpected state fields')
    for key, value in expected.items():
        require(instant(actual[key]) == instant(value) if key == 'lastSeenAt' else actual[key] == value,
                'State mismatch: ' + key)


def kafka_record(raw, expected_key):
    # Kafka CLI may emit startup/logging lines on stdout alongside formatted records.
    records = [line.split('\t', 1) for line in raw.splitlines()
               if line.startswith(expected_key + '\t')]
    require(len(records) == 1, 'Expected exactly one Kafka record with the vehicle key')
    key, value = records[0]
    return key, json.loads(value)


class Nominal:
    def __init__(self, stack):
        self.stack = stack
        self.evidence = []

    def record(self, name, details):
        self.evidence.append(dict(check=name, details=details))
        (self.stack.output / 'checks.json').write_text(json.dumps(self.evidence, indent=2))

    def sql(self, query):
        text = self.stack.call('exec', '-T', 'postgres', 'sh', '-ec',
            'exec psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"',
            'sh', "BEGIN READ ONLY; SET LOCAL statement_timeout='5s'; " + query + '; COMMIT;')
        return [json.loads(line) for line in text.splitlines() if line.startswith('{')]

    def redis(self, vehicle, operation='GET'):
        return self.stack.call('exec', '-T', 'redis', 'redis-cli', '--raw', operation,
                               'vehicle:last:' + str(uuid.UUID(vehicle))).strip()

    def offsets(self):
        text = self.stack.kafka('kafka-get-offsets.sh', '--topic', self.stack.env['KAFKA_TOPIC_RAW'],
                                '--time', '-1')
        return {int(line.rsplit(':', 2)[1]): int(line.rsplit(':', 1)[1])
                for line in text.splitlines() if line.strip()}

    def kafka_event(self, message, previous):
        current = self.offsets()
        changed = [partition for partition in current if current[partition] != previous[partition]]
        require(len(changed) == 1 and current[changed[0]] == previous[changed[0]] + 1,
                'Expected one new raw record')
        partition = changed[0]
        raw = self.stack.kafka('kafka-console-consumer.sh', '--topic', self.stack.env['KAFKA_TOPIC_RAW'],
            '--partition', str(partition), '--offset', str(previous[partition]), '--max-messages', '1',
            '--timeout-ms', '5000', '--consumer-property', 'enable.auto.commit=false',
            '--property', 'print.key=true')
        key, event = kafka_record(raw, message['vehicleId'])
        require(key == message['vehicleId'], 'Kafka key must be vehicleId')
        require(event['eventVersion'] == 1, 'Unexpected Kafka eventVersion')
        for field in ('messageId', 'vehicleId', 'sequenceNumber'):
            require(event[field] == message[field], 'Kafka mismatch: ' + field)
        require(instant(event['observedAt']) == instant(message['observedAt']), 'Kafka observedAt mismatch')
        require(event['telemetry'] == {field: message[field] for field in FIELDS}, 'Kafka telemetry mismatch')
        return event, dict(topic=self.stack.env['KAFKA_TOPIC_RAW'], partition=partition,
                           offset=previous[partition], key=key, event=event)

    def service_time(self, app):
        # Docker Desktop runs a separate clock; compare timestamps on the service host.
        seconds = int(self.stack.call('exec', '-T', app, 'date', '+%s').strip())
        return dt.datetime.fromtimestamp(seconds, UTC)

    def verify_message(self, stream, message):
        previous = self.offsets()
        sent_at = self.service_time('telemetry-gateway')
        stream.sendall(frame(message))
        ack(stream, message)
        acknowledged_at = self.service_time('telemetry-gateway') + dt.timedelta(seconds=1)
        self.record('TCP ACK', dict(messageId=message['messageId'], status='ACCEPTED',
                                    protocolVersion=1, persistentConnection=True))
        event, observed = self.kafka_event(message, previous)
        require(sent_at <= instant(event['receivedAt']) <= acknowledged_at,
                'Gateway receivedAt outside send/ACK interval')
        self.record('Kafka record', observed)
        mid = str(uuid.UUID(message['messageId']))
        rows = wait(lambda: self.sql(f"SELECT row_to_json(t) FROM telemetry_samples t WHERE message_id='{mid}'"),
                    'PostgreSQL sample missing')
        require(len(rows) == 1, 'Duplicate SQL sample')
        row = rows[0]
        for field, column in (('messageId', 'message_id'), ('vehicleId', 'vehicle_id'),
                              ('sequenceNumber', 'sequence_number')):
            require(row[column] == message[field], 'SQL mismatch: ' + column)
        require(instant(row['observed_at']) == instant(message['observedAt']), 'SQL observedAt mismatch')
        # PostgreSQL rounds to microseconds, Java Instant/Kafka can retain nanoseconds.
        require(abs((instant(row['received_at']) - instant(event['receivedAt'])).total_seconds()) <= .000001,
                'SQL receivedAt mismatch')
        require(instant(row['processed_at']) >= instant(row['received_at']), 'SQL processing timestamp mismatch')
        for field, column in SQL_FIELDS.items():
            require(row[column] == message[field], 'SQL mismatch: ' + column)
        self.record('PostgreSQL sample', row)

        def projection():
            text = self.redis(message['vehicleId'])
            if not text:
                return False
            value = json.loads(text)
            # Previous projection is legitimate while asynchronous update is pending.
            if value.get('lastSequenceNumber') != message['sequenceNumber']:
                return False
            compare_state(value, message)
            return value

        value = wait(projection, 'Processor Redis projection missing before State API')
        ttl = int(self.redis(message['vehicleId'], 'TTL'))
        require(0 < ttl <= 300, 'Projection TTL outside default 5m contract')
        self.record('Redis projection BEFORE State API', dict(state=value, ttlSeconds=ttl))
        api = self.stack.urls['fleet-api']
        before = metrics(api)
        started = self.service_time('fleet-api')
        code, _, body = request(api + '/api/v1/vehicles/' + message['vehicleId'] + '/state')
        finished = self.service_time('fleet-api') + dt.timedelta(seconds=1)
        require(code == 200, 'State API status mismatch')
        compare_state(body, message, api=True)
        require(type(body['stale']) is bool, 'State stale must be boolean')
        age_start = (started - instant(message['observedAt'])).total_seconds()
        age_end = (finished - instant(message['observedAt'])).total_seconds()
        if age_start > 60:
            require(body['stale'], 'Expected stale sample')
        elif age_end <= 60:
            require(not body['stale'], 'Expected fresh sample')
        else:
            raise AssertionError('Freshness boundary crossed; retry with a new test fixture')
        after = metrics(api)
        hit = after['fleetpulse_api_cache_hits_total'] - before['fleetpulse_api_cache_hits_total']
        fallback = after['fleetpulse_api_cache_fallback_total'] - before['fleetpulse_api_cache_fallback_total']
        require(hit == 1 and fallback == 0, 'State API must serve one cache hit without fallback')
        self.record('State API cache hit', dict(http=code, body=body, cacheHitsDelta=hit, fallbackDelta=fallback))

    def run(self):
        api = self.stack.urls['fleet-api']
        suffix = uuid.uuid4().hex[:8]
        registration = dict(externalCode='FP050-' + suffix, plate='E' + suffix,
                            serviceIntervalKm=15000, nextServiceAtKm=25000)
        code, headers, vehicle = request(api + '/api/v1/vehicles', registration)
        require(code == 201, 'Registration must return HTTP 201')
        vid = str(uuid.UUID(vehicle['id']))
        require(vehicle['status'] == 'ACTIVE', 'New vehicle must be ACTIVE')
        for key, value in registration.items():
            require(vehicle[key] == value, 'Registration mismatch: ' + key)
        require(urllib.parse.urlparse(headers['Location']).path == '/api/v1/vehicles/' + vid,
                'Registration Location mismatch')
        code, _, fetched = request(api + '/api/v1/vehicles/' + vid)
        require(code == 200 and set(fetched) == set(vehicle), 'Registered vehicle GET mismatch')
        for key in vehicle:
            if key == 'createdAt':
                require(abs((instant(fetched[key]) - instant(vehicle[key])).total_seconds()) <= .000001,
                        'Registered vehicle createdAt mismatch')
            else:
                require(fetched[key] == vehicle[key], 'Registered vehicle GET mismatch: ' + key)
        self.record('REST registration and GET', dict(http=201, vehicle=vehicle))
        require(not self.redis(vid), 'Redis must be empty before telemetry')
        self.record('Redis initially absent', dict(vehicleId=vid))
        # Keep synthetic values nominal even if alert thresholds were customized in .env.
        maximum = float(self.stack.env['TELEMETRY_ALERT_MAXIMUM_ENGINE_TEMPERATURE_C'])
        minimum = float(self.stack.env['TELEMETRY_ALERT_MINIMUM_BATTERY_VOLTAGE'])
        with socket.create_connection((self.stack.gateway[0], int(self.stack.gateway[1])), timeout=5) as stream:
            stream.settimeout(7)
            for sequence in (0, 1):
                message = dict(protocolVersion=1, messageId=str(uuid.uuid4()), vehicleId=vid,
                    sequenceNumber=sequence, observedAt=dt.datetime.now(UTC).isoformat(),
                    speedKmh=60.25 + sequence, engineTemperatureC=maximum - 2 - sequence,
                    batteryVoltage=minimum + 1 + sequence, odometerKm=10000 + sequence,
                    latitude=41.9 + sequence * .001, longitude=12.5 + sequence * .001)
                self.verify_message(stream, message)
        totals = self.sql(f"SELECT json_build_object('samples', (SELECT count(*) FROM telemetry_samples WHERE vehicle_id='{vid}'), 'alerts', (SELECT count(*) FROM maintenance_alerts WHERE vehicle_id='{vid}'))")
        require(totals == [{'samples': 2, 'alerts': 0}], 'Unexpected sample or alert count')
        offsets = self.stack.offsets()
        require(offsets['KAFKA_TOPIC_RAW'] == 2 and offsets['KAFKA_TOPIC_REJECTED'] == 0
                and offsets['KAFKA_TOPIC_DEAD_LETTER'] == 0, 'Unexpected Kafka terminal records')
        wait(lambda: self.stack.lag()['total'] == 0, 'Processor lag not drained')
        self.record('Final reconciliation', dict(sql=totals[0], topicRecordCounts=offsets, lag=0))
        (self.stack.output / 'summary.json').write_text(json.dumps(dict(result='passed',
            checks=len(self.evidence), messages=2, sameTcpConnection=True, project=self.stack.project), indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    stack = Stack(output)
    stack.project = 'fp050-' + uuid.uuid4().hex[:10]
    stack.base[3] = stack.project
    try:
        print('Building isolated stack ' + stack.project, flush=True)
        stack.prepare()
        Nominal(stack).run()
    except BaseException as error:
        # Keep failures useful without dumping configuration, HTTP payloads or subprocess stderr.
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
    print('Passed: 13 checks, 2 messages; isolated resources removed', flush=True)


if __name__ == '__main__':
    main()
