#!/usr/bin/env python3
"""FP-047 isolated Compose acceptance test. Python standard library only."""
import argparse
import hashlib
import concurrent.futures
import datetime as dt
import json
import math
from pathlib import Path
import random
import socket
import struct
import subprocess
import threading
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
APPS = ('fleet-api', 'telemetry-gateway', 'telemetry-processor')
SERVICES = ('postgres', 'redis', 'kafka', 'flyway', 'kafka-init', *APPS)
MEMORY = {'postgres': 512, 'redis': 128, 'kafka': 1024, **dict.fromkeys(APPS, 512)}


def command(args, timeout=60):
    result = subprocess.run(args, cwd=ROOT, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        # Compose output may contain environment secrets; retain only the failing operation.
        raise RuntimeError(f'Command failed ({result.returncode}): {args[0:3]}')
    return result.stdout


def http(url, body=None):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=5) as response:
        return response.read().decode()


def frame(message):
    payload = json.dumps(message, separators=(',', ':')).encode()
    if not 0 < len(payload) <= 65536:
        raise ValueError('Invalid payload size')
    return struct.pack('!I', len(payload)) + payload


def read_exact(stream, size):
    result = bytearray()
    while len(result) < size:
        chunk = stream.recv(size - len(result))
        if not chunk:
            raise EOFError('Incomplete ACK')
        result.extend(chunk)
    return bytes(result)


def ack(stream, message):
    size = struct.unpack('!I', read_exact(stream, 4))[0]
    if not 0 < size <= 65536:
        raise ValueError('Invalid ACK length')
    response = json.loads(read_exact(stream, size))
    if (response.get('protocolVersion') != 1 or response.get('status') != 'ACCEPTED'
            or response.get('messageId') != message['messageId']):
        raise ValueError(f'Unexpected ACK: {response}')


def flatten(value, prefix=''):
    result = {}
    for key, item in value.items():
        key = prefix + key
        if isinstance(item, dict):
            result.update(flatten(item, key + '.'))
        else:
            result[key] = item
    return result


def percentile(values, q):
    return sorted(values)[max(0, math.ceil(q * len(values)) - 1)] if values else None


def metrics(url):
    result = {}
    for line in http(url + '/actuator/prometheus').splitlines():
        if line and not line.startswith('#'):
            name, value = line.rsplit(" ", 1)
            result[name] = float(value)
    return result


def worker(vehicle, index, host, port, start, args, mixed):
    rng = random.Random(args.seed + index)
    records = []
    stream = None
    try:
        stream = socket.create_connection((host, port), timeout=1)
        for slot in range(round(args.duration / args.interval)):
            due = start + slot * args.interval
            time.sleep(max(0, due - time.monotonic()))
            lateness = max(0, time.monotonic() - due)
            message = dict(protocolVersion=1, messageId=str(uuid.uuid4()), vehicleId=vehicle,
                           sequenceNumber=slot, observedAt=dt.datetime.now(dt.timezone.utc).isoformat(),
                           speedKmh=60, engineTemperatureC=90, batteryVoltage=12.6,
                           odometerKm=10000 + slot, latitude=41.9, longitude=12.5)
            packet = frame(message)
            record = dict(messageId=message['messageId'], vehicleId=vehicle, slot=slot,
                          latenessMs=lateness * 1000, accepted=False, duplicate=False,
                          disconnected=False, transmissions=0)
            records.append(record)
            try:
                # One independent fault decision per unique scheduled frame.
                if mixed and rng.random() < .01:
                    record['disconnected'] = True
                    stream.sendall(packet[:len(packet)//2])
                    stream.close()
                    stream = socket.create_connection((host, port), timeout=1)
                stream.sendall(packet)
                record['transmissions'] += 1
                ack(stream, message)
                record['accepted'] = True
                if mixed and rng.random() < .02:
                    stream.sendall(packet)
                    record['transmissions'] += 1
                    ack(stream, message)
                    record['duplicate'] = True
            except (OSError, EOFError, ValueError) as error:
                record['error'] = str(error)
                # Never hide an ambiguous send with an unaccounted retry.
                stream.close()
                stream = socket.create_connection((host, port), timeout=1)
        time.sleep(max(0, start + args.duration - time.monotonic()))
    finally:
        if stream:
            stream.close()
    return records


class Stack:
    def __init__(self, output):
        self.project = 'fp047-' + uuid.uuid4().hex[:10]
        self.output = output
        self.config = output / 'compose.private.json'
        self.base = ['docker', 'compose', '-p', self.project, '-f', str(self.config)]
        self.ids = {}
        self.urls = {}
        self.env = {}

    def call(self, *args, timeout=60):
        return command([*self.base, *args], timeout)

    def prepare(self):
        config = json.loads(command(['docker', 'compose', 'config', '--format', 'json']))
        config.pop('name', None)
        for section in ('volumes', 'networks'):
            for resource in config.get(section, {}).values():
                resource.pop('name', None)
                if resource.get('external'):
                    raise RuntimeError('External resources are not allowed in isolated load tests')
        config['services'] = {name: service for name, service in config['services'].items()
                              if name in SERVICES}
        for name, service in config['services'].items():
            service.pop('container_name', None)
            service.pop('image', None) if 'build' in service else None
            service['restart'] = 'no'
            for mapping in service.get('ports', []):
                mapping['published'] = '0'
                mapping['host_ip'] = '127.0.0.1'
            if name in MEMORY:
                service['mem_limit'] = MEMORY[name] * 1024 * 1024
                service['cpus'] = 2.0
            if name in APPS:
                service['environment']['JAVA_TOOL_OPTIONS'] = (
                    '-XX:MaxRAMPercentage=65.0 -XX:+ExitOnOutOfMemoryError')
                service['environment']['FLEETPULSE_LOG_FORMAT'] = 'ecs'
        self.env = config['services']['telemetry-processor']['environment']
        self.config.write_text(json.dumps(config))
        self.config.chmod(0o600)
        self.call('up', '-d', '--build', *APPS, timeout=900)
        for name in MEMORY:
            self.ids[name] = self.call('ps', '-q', name).strip()
        for name in APPS:
            address = self.call('port', name, '8080').strip()
            self.urls[name] = 'http://' + address
        self.gateway = self.call('port', 'telemetry-gateway', '7000').strip().rsplit(':', 1)
        deadline = time.monotonic() + 120
        for name, url in self.urls.items():
            while True:
                try:
                    health = json.loads(http(url + "/actuator/health/readiness"))
                    if health.get("status") != "UP":
                        raise RuntimeError("Application is not ready")
                    metrics(url)
                    break
                except Exception:
                    if time.monotonic() > deadline:
                        raise RuntimeError(f'{name} not ready')
                    time.sleep(1)

    def sql(self, query):
        return self.call('exec', '-T', 'postgres', 'psql', '-U',
                         self.env['SPRING_DATASOURCE_USERNAME'], '-d',
                         self.env['SPRING_DATASOURCE_URL'].rsplit('/', 1)[-1],
                         '-At', '-c', query).strip()

    def kafka(self, script, *options):
        return self.call('exec', '-T', 'kafka', '/opt/kafka/bin/' + script,
                         '--bootstrap-server', 'kafka:19092', *options)

    def lag(self):
        raw = self.kafka('kafka-consumer-groups.sh', '--describe', '--group',
                         self.env['KAFKA_CONSUMER_GROUP_ID'])
        rows = [line.split() for line in raw.splitlines()
                if self.env['KAFKA_TOPIC_RAW'] in line]
        if not rows or any(len(row) < 6 or not row[4].isdigit() for row in rows):
            raise RuntimeError('Consumer lag unavailable')
        # A fresh isolated topic may have partitions without any committed offset.
        # CLI renders their lag as '-'. Empty partitions have zero lag; otherwise
        # count their complete log from offset zero until a commit is available.
        lag = sum(max(0, int(row[4]) - (int(row[3]) if row[3].isdigit() else 0)) for row in rows)
        return dict(total=lag, rows=rows)

    def offsets(self):
        return {key: sum(int(line.rsplit(':', 1)[1]) for line in
                        self.kafka('kafka-get-offsets.sh', '--topic', self.env[key],
                                   '--time', '-1').splitlines() if line.strip())
                for key in ('KAFKA_TOPIC_RAW', 'KAFKA_TOPIC_REJECTED', 'KAFKA_TOPIC_DEAD_LETTER')}

    def sample(self):
        stats = command(['docker', 'stats', '--no-stream', '--format', '{{json .}}',
                         *self.ids.values()])
        return dict(at=dt.datetime.now(dt.timezone.utc).isoformat(),
                    stats=[json.loads(line) for line in stats.splitlines()],
                    metrics={name: metrics(url) for name, url in self.urls.items()})

    def logs(self):
        text = self.call('logs', '--no-color', '--no-log-prefix', 'telemetry-processor')
        (self.output / 'processor.log').write_text(text)
        result = []
        for line in text.splitlines():
            try:
                entry = flatten(json.loads(line))
                if entry.get('event.action') == 'telemetry.event.persisted':
                    result.append(entry)
            except ValueError:
                pass
        return result

    def close(self):
        try:
            if self.config.exists():
                self.call('down', '-v', '--remove-orphans', timeout=90)
        finally:
            self.config.unlink(missing_ok=True)


def scenario(stack, args, name):
    prefix = uuid.uuid4().hex[:8]
    vehicles = [json.loads(http(stack.urls['fleet-api'] + '/api/v1/vehicles',
                dict(externalCode=f'FP047-{prefix}-{i}', plate=f'F{prefix}{i:02}',
                     serviceIntervalKm=15000, nextServiceAtKm=25000)))['id']
                for i in range(args.vehicles)]
    # Warm-up uses separate vehicles/data and is performed by a separate smoke scenario.
    before_offsets = stack.offsets()
    samples = [stack.sample()]
    start = time.monotonic() + 3
    errors = []
    stop = threading.Event()
    def monitor():
        while not stop.wait(5):
            try:
                sample = stack.sample()
                if len(samples) % 6 == 0:
                    sample['consumerLag'] = stack.lag()
                samples.append(sample)
            except Exception as error:
                errors.append(str(error))
    thread = threading.Thread(target=monitor)
    thread.start()
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.vehicles) as pool:
            futures = [pool.submit(worker, v, i, stack.gateway[0], int(stack.gateway[1]),
                                   start, args, name == 'mixed') for i, v in enumerate(vehicles)]
            records = [record for future in futures for record in future.result()]
        expected = {record['messageId'] for record in records}
        wanted = args.vehicles * round(args.duration / args.interval)
        vehicle_sql = ','.join("'" + v + "'" for v in vehicles)
        query = f'SELECT message_id FROM telemetry_samples WHERE vehicle_id IN ({vehicle_sql})'
        deadline = time.monotonic() + 60
        while True:
            stored = set(stack.sql(query).splitlines())
            lag = stack.lag()
            if stored == expected and lag['total'] == 0:
                break
            if time.monotonic() > deadline:
                errors.append('Drain deadline exceeded')
                break
            time.sleep(1)
        samples.append(stack.sample())
    finally:
        stop.set()
        thread.join(timeout=15)
    after_offsets = stack.offsets()
    deltas = {key: value - before_offsets[key] for key, value in after_offsets.items()}
    if deltas['KAFKA_TOPIC_RAW'] != sum(r['transmissions'] for r in records):
        errors.append('Raw Kafka record count differs from full transmissions')
    if deltas['KAFKA_TOPIC_REJECTED'] or deltas['KAFKA_TOPIC_DEAD_LETTER']:
        errors.append('Unexpected rejected/dead-letter records')
    peak_connections = max(s['metrics']['telemetry-gateway'].get(
        'fleetpulse_gateway_connections_active', 0) for s in samples)
    if peak_connections < args.vehicles:
        errors.append('Concurrent vehicle connections not observed')
    logs = [entry for entry in stack.logs() if entry.get('messageId') in expected]
    durations = [entry['pipeline.persistence.latency.ms'] for entry in logs
                 if entry.get('pipeline.persistence.clock.valid') is True]
    if len(logs) != wanted or len(durations) != wanted:
        errors.append('Missing/invalid post-commit latency observations')
    if len(records) != wanted or stored != expected or not all(r['accepted'] for r in records):
        errors.append('Offered/ACK/persisted messages differ')
    late = sum(r['latenessMs'] >= args.interval * 1000 for r in records)
    if late:
        errors.append('Missed scheduled slots')
    p95 = percentile(durations, .95)
    if name == 'mixed' and wanted >= 7500 and not all(
            any(r[key] for r in records) for key in ('duplicate', 'disconnected')):
        errors.append('Reference mixed scenario did not exercise both fault types')
    if name != 'warmup' and (p95 is None or p95 >= 2000):
        errors.append('Pipeline p95 is not below 2 seconds')
    # Enforced budgets plus margin: RSS/container working set <90%, heap <90% max.
    for sample in samples:
        for stat in sample['stats']:
            if float(stat['CPUPerc'].rstrip('%')) > 205:
                errors.append('Container CPU budget exceeded')
            if float(stat['MemPerc'].rstrip('%')) >= 90:
                errors.append('Container memory budget exceeded')
        for app, values in sample['metrics'].items():
            used = sum(v for k, v in values.items() if k.startswith('jvm_memory_used_bytes{') and 'area="heap"' in k)
            maximum = sum(v for k, v in values.items() if k.startswith('jvm_memory_max_bytes{') and 'area="heap"' in k and v > 0)
            if not maximum or used >= .9 * maximum:
                errors.append(f'{app}: heap budget exceeded/unavailable')
    state = json.loads(command(['docker', 'inspect', *stack.ids.values()]))
    if any(c['State']['OOMKilled'] or not c['State']['Running'] or c['RestartCount'] for c in state):
        errors.append('Container failure/restart/OOM')
    alerts = int(stack.sql(f'SELECT count(*) FROM maintenance_alerts WHERE vehicle_id IN ({vehicle_sql})'))
    if alerts:
        errors.append('Unexpected alerts for normal telemetry')
    report = dict(scenario=name, vehicles=args.vehicles, durationSeconds=args.duration,
                  intervalSeconds=args.interval, seed=args.seed, expected=wanted,
                  offered=len(records), accepted=sum(r['accepted'] for r in records),
                  persisted=len(stored), duplicates=sum(r['duplicate'] for r in records),
                  disconnects=sum(r['disconnected'] for r in records), missedSlots=late,
                  p50Ms=percentile(durations, .5), p95Ms=p95, p99Ms=percentile(durations, .99),
                  maxMs=max(durations, default=None), latencySamples=len(durations),
                  finalLag=lag, kafkaRecordDeltas=deltas, peakConnections=peak_connections,
                  alerts=alerts, errors=sorted(set(errors)),
                  fullReference=args.vehicles == 50 and args.duration == 300 and args.interval == 2)
    (stack.output / f'{name}-report.json').write_text(json.dumps(report, indent=2))
    (stack.output / f'{name}-frames.json').write_text(json.dumps(records))
    (stack.output / f'{name}-resources.json').write_text(json.dumps(samples))
    (stack.output / f'{name}-latencies.json').write_text(json.dumps(logs))
    print(json.dumps(report), flush=True)
    if errors:
        raise RuntimeError(f'{name} acceptance criteria failed')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--duration', type=float, default=300)
    parser.add_argument('--interval', type=float, default=2)
    parser.add_argument('--vehicles', type=int, default=50)
    parser.add_argument('--seed', type=int, default=47)
    parser.add_argument('--scenario', choices=['baseline', 'mixed', 'both'], default='both')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if (args.duration <= 0 or args.interval <= 0 or not 1 <= args.vehicles <= 50
            or not math.isclose(args.duration / args.interval, round(args.duration / args.interval))):
        parser.error('Require 1..50 vehicles and positive duration divisible by interval')
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=False)
    stack = Stack(args.output)
    try:
        print(f'Building isolated stack {stack.project}', flush=True)
        stack.prepare()
        metadata = dict(commit=command(['git', 'rev-parse', 'HEAD']).strip(),
                        dirty=bool(command(['git', 'status', '--porcelain']).strip()),
                        docker=json.loads(command(['docker', 'info', '--format', '{{json .}}'])),
                        memoryMiB=MEMORY, cpuLimitPerContainer=2,
                        heapMaxPercentage=65, project=stack.project,
                        containers=[dict(name=c['Name'], image=c['Config']['Image'], imageId=c['Image'])
                                    for c in json.loads(command(['docker', 'inspect', *stack.ids.values()]))],
                        sourceDiffSha256=hashlib.sha256(command(['git', 'diff', 'HEAD']).encode()).hexdigest(),
                        harnessSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())
        # Persist only non-sensitive host/build details.
        metadata['docker'] = {k: metadata['docker'].get(k) for k in
                              ('ServerVersion', 'NCPU', 'MemTotal', 'Architecture', 'OperatingSystem')}
        (args.output / 'environment.json').write_text(json.dumps(metadata, indent=2))
        warmup = argparse.Namespace(**vars(args))
        warmup.duration, warmup.vehicles = 10, args.vehicles
        warmup.interval = min(args.interval, 2)
        scenario(stack, warmup, 'warmup')
        for name in ('baseline', 'mixed') if args.scenario == 'both' else (args.scenario,):
            print(f'Starting {name}: {args.duration}s / {args.vehicles} vehicles', flush=True)
            scenario(stack, args, name)
    except BaseException as error:
        (args.output / 'failure.json').write_text(json.dumps(dict(error=str(error))))
        raise
    finally:
        stack.close()


if __name__ == '__main__':
    main()
