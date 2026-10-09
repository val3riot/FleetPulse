#!/usr/bin/env python3
"""FP-048: real HTTP probes under isolated dependency outage/recovery."""
import argparse
import datetime as dt
import json
from pathlib import Path
import socket
import sys
import time
import urllib.error
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'load'))
from run import APPS, Stack, ack, frame, http, metrics


def probe(url):
    start = time.monotonic()
    try:
        body = http(url)
        code = 200
    except urllib.error.HTTPError as error:
        code, body = error.code, error.read().decode()
    return code, json.loads(body), (time.monotonic() - start) * 1000


class Verification:
    def __init__(self, stack):
        self.stack = stack
        self.evidence = []

    def check(self, stage, app, group, code, deadline=15):
        url = self.stack.urls[app] + '/actuator/health' + ('/' + group if group else '')
        until = time.monotonic() + deadline
        while True:
            actual, body, elapsed = probe(url)
            if actual == code:
                break
            if time.monotonic() >= until:
                raise AssertionError(f'{stage}/{app}/{group}: {actual}, expected {code}')
            time.sleep(.3)
        if elapsed >= 5000:
            raise AssertionError(f'Probe exceeded 5s: {app}/{group}')
        if set(body) - {'status', 'groups'} or body['status'] != ('UP' if code == 200 else 'DOWN'):
            raise AssertionError(f'Unexpected public health response: {body}')
        self.evidence.append(dict(stage=stage, app=app, group=group or 'root',
                                  code=actual, durationMs=elapsed, body=body))
        self.save()

    def all_healthy(self, stage, deadline=15):
        for app in APPS:
            for group in ('', 'liveness', 'readiness'):
                self.check(stage, app, group, 200, deadline)

    def save(self):
        (self.stack.output / 'checks.json').write_text(json.dumps(self.evidence, indent=2))

    def send(self, vehicle, sequence):
        message = dict(protocolVersion=1, messageId=str(uuid.uuid4()), vehicleId=vehicle,
                       sequenceNumber=sequence, observedAt=dt.datetime.now(dt.timezone.utc).isoformat(),
                       speedKmh=60, engineTemperatureC=90, batteryVoltage=12.6,
                       odometerKm=10000 + sequence, latitude=41.9, longitude=12.5)
        with socket.create_connection((self.stack.gateway[0], int(self.stack.gateway[1])), timeout=5) as stream:
            stream.sendall(frame(message))
            ack(stream, message)
        until = time.monotonic() + 20
        while self.stack.sql(f"SELECT count(*) FROM telemetry_samples WHERE message_id='{message['messageId']}'") != '1':
            if time.monotonic() >= until:
                raise AssertionError('Accepted telemetry not persisted')
            time.sleep(.2)
        return message

    def state(self, vehicle, code):
        url = self.stack.urls['fleet-api'] + '/api/v1/vehicles/' + vehicle + '/state'
        actual, body, elapsed = probe(url)
        if actual != code or elapsed >= 5000:
            raise AssertionError(f'State API: HTTP {actual}, duration {elapsed}ms')
        self.evidence.append(dict(stage='state-api', vehicle=vehicle, code=actual,
                                  durationMs=elapsed, body=body))
        self.save()
        return body

    def start_times(self):
        return {app: metrics(url)['process_start_time_seconds'] for app, url in self.stack.urls.items()}

    def run(self):
        self.all_healthy('healthy')
        suffix = uuid.uuid4().hex[:8]
        vehicle = json.loads(http(self.stack.urls['fleet-api'] + '/api/v1/vehicles',
                            dict(externalCode='FP048-' + suffix, plate='H' + suffix,
                                 serviceIntervalKm=15000, nextServiceAtKm=25000)))['id']
        self.send(vehicle, 0)
        self.state(vehicle, 200)
        print('Testing Redis outage and cold application startup', flush=True)
        self.stack.call('stop', 'redis')
        self.all_healthy('redis-down')
        self.send(vehicle, 1)
        until = time.monotonic() + 5
        while metrics(self.stack.urls['telemetry-processor'])['fleetpulse_redis_update_failures_total'] < 1:
            if time.monotonic() >= until:
                raise AssertionError('Redis projection failure was not observed within 5s')
            time.sleep(.1)
        body = self.state(vehicle, 200)
        if body['lastSequenceNumber'] != 1:
            raise AssertionError('Redis outage did not use the latest persisted state')
        self.stack.call('restart', 'fleet-api', 'telemetry-processor')
        for app in ('fleet-api', 'telemetry-processor'):
            # Docker may reassign published port 0 when a container restarts.
            address = self.stack.call('port', app, '8080').strip()
            self.stack.urls[app] = 'http://' + address
        # During restart the HTTP port may be temporarily closed; wait for the endpoints first.
        for app in ('fleet-api', 'telemetry-processor'):
            until = time.monotonic() + 120
            while True:
                try:
                    probe(self.stack.urls[app] + '/actuator/health/liveness')
                    break
                except (OSError, urllib.error.URLError):
                    if time.monotonic() >= until:
                        raise
                    time.sleep(.5)
        self.all_healthy('redis-down-cold-start', deadline=120)
        self.state(vehicle, 200)
        self.stack.call('start', 'redis')
        self.all_healthy('redis-recovered')
        self.state(vehicle, 200)  # Fallback/read repair repopulates the fresh cache.
        initial = self.start_times()
        print('Testing PostgreSQL outage and recovery', flush=True)
        self.stack.call('stop', 'postgres')
        for app in ('fleet-api', 'telemetry-processor'):
            self.check('postgres-down', app, 'readiness', 503)
            self.check('postgres-down', app, '', 503)
            self.check('postgres-down', app, 'liveness', 200)
        self.check('postgres-down', 'telemetry-gateway', 'readiness', 200)
        self.state(vehicle, 200)  # Cache hit remains a valid application response.
        self.state(str(uuid.uuid4()), 503)  # Cache miss requires the unavailable database.
        self.stack.call('start', 'postgres')
        self.all_healthy('postgres-recovered', deadline=60)
        self.send(vehicle, 2)
        print('Testing Kafka outage and recovery', flush=True)
        self.stack.call('stop', 'kafka')
        for app in ('telemetry-gateway', 'telemetry-processor'):
            self.check('kafka-down', app, 'readiness', 503)
            self.check('kafka-down', app, '', 503)
            self.check('kafka-down', app, 'liveness', 200)
        self.check('kafka-down', 'fleet-api', 'readiness', 200)
        self.stack.call('start', 'kafka')
        self.all_healthy('kafka-recovered', deadline=90)
        self.send(vehicle, 3)
        self.state(vehicle, 200)
        if self.start_times() != initial:
            raise AssertionError('An application restarted during PostgreSQL/Kafka recovery')
        (self.stack.output / 'summary.json').write_text(json.dumps(dict(
            checks=len(self.evidence), result='passed', finalSequence=3,
            applicationRestartsDuringDatabaseKafkaRecovery=0), indent=2))
        print(f'Passed: {len(self.evidence)} checks', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=False)
    stack = Stack(args.output)
    # The shared harness guarantees unique project/ports/volumes and scoped cleanup.
    stack.project = 'fp048-' + uuid.uuid4().hex[:10]
    stack.base[3] = stack.project
    try:
        print(f'Building isolated stack {stack.project}', flush=True)
        stack.prepare()
        Verification(stack).run()
    except BaseException as error:
        (args.output / 'failure.json').write_text(json.dumps(dict(error=str(error))))
        raise
    finally:
        stack.close()


if __name__ == '__main__':
    main()
