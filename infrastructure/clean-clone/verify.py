#!/usr/bin/env python3
"""Verify a fresh Git clone, its quality gates and an isolated complete deployment."""
import argparse
import copy
import datetime as dt
import json
import os
from pathlib import Path
import signal
import socket
import struct
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def inside(path, directory):
    try:
        Path(path).resolve().relative_to(Path(directory).resolve())
        return True
    except ValueError:
        return False


def isolated_config(source, checkout):
    """Remove deployment names/ports that could refer to the operator's stack."""
    config = copy.deepcopy(source)
    config.pop('name', None)
    for section in ('volumes', 'networks'):
        for resource in config.get(section, {}).values():
            require(not resource.get('external'), 'External Docker resources are forbidden')
            resource.pop('name', None)
    require(not config.get('secrets'), 'Secrets are not part of the example deployment')
    limits = {'kafka': '1024m', 'redis': '128m', 'fleet-dashboard': '128m'}
    for name, service in config['services'].items():
        require(not service.get('privileged') and service.get('network_mode') != 'host', 'Unsafe network/container mode')
        service.pop('container_name', None)
        service['restart'] = 'no'
        service.setdefault('mem_limit', limits.get(name, '512m'))
        service.setdefault('cpus', 2.0)
        if 'build' in service:
            require(inside(service['build']['context'], checkout), 'Build context outside clone')
            service.pop('image', None)
        for port in service.get('ports', []):
            require(isinstance(port, dict), 'Expected resolved Compose port')
            port['published'] = '0'
            port['host_ip'] = '127.0.0.1'
        for mount in service.get('volumes', []):
            if mount.get('type') == 'bind':
                require(inside(mount['source'], checkout), 'Bind mount outside clone')
    return config


def http(url, body=None):
    payload = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(url, data=payload, headers={'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            return response.status, response.headers, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.headers, error.read()


def json_http(url, body=None):
    status, headers, payload = http(url, body)
    require(status in (200, 201), 'Unexpected HTTP status: ' + str(status))
    return status, headers, json.loads(payload)


def wait(check, description, timeout=120):
    deadline = time.monotonic() + timeout
    while True:
        result = check()
        if result:
            return result
        require(time.monotonic() < deadline, 'Timeout: ' + description)
        time.sleep(.25)


def read_exact(stream, length):
    result = b''
    while len(result) < length:
        chunk = stream.recv(length - len(result))
        require(bool(chunk), 'EOF during framed ACK')
        result += chunk
    return result


def send_message(host, port, message):
    payload = json.dumps(message, separators=(',', ':')).encode()
    with socket.create_connection((host, port), timeout=3) as stream:
        stream.settimeout(7)
        stream.sendall(struct.pack('!I', len(payload)) + payload)
        length = struct.unpack('!I', read_exact(stream, 4))[0]
        require(0 < length <= 65536, 'Invalid ACK frame size')
        ack = json.loads(read_exact(stream, length))
    require(ack.get('messageId') == message['messageId'] and ack.get('status') == 'ACCEPTED', 'Expected correlated ACCEPTED')
    return ack


def java_results(checkout):
    result = dict(tests=0, failures=0, errors=0, skipped=0, suites=0)
    for path in checkout.glob('**/target/surefire-reports/TEST-*.xml'):
        suite = ET.parse(path).getroot()
        result['suites'] += 1
        for field in ('tests', 'failures', 'errors', 'skipped'):
            result[field] += int(suite.get(field, '0'))
    require(result['tests'] > 0 and not any(result[key] for key in ('failures', 'errors', 'skipped')), 'Java test reports are incomplete or failed')
    return result


class Verification:
    def __init__(self, output):
        self.output = output.resolve()
        self.output.mkdir(parents=True, exist_ok=False)
        self.checkout = self.output / 'repository'
        self.project = 'fleet-clean-' + uuid.uuid4().hex[:10]
        self.private = self.output / 'compose.private.json'
        self.environment = None
        self.deploy_attempted = False
        self.evidence = []
        self.existing_volumes = set()
        self.created_volumes = set()
        self.base = ['docker', 'compose', '-p', self.project, '-f', str(self.private)]

    def record(self, name, details):
        self.evidence.append(dict(check=name, details=details))
        (self.output / 'checks.json').write_text(json.dumps(self.evidence, indent=2) + '\n')
        print('PASS ' + name, flush=True)

    def run(self, args, label, cwd=None, timeout=120, capture=False):
        path = self.output / (label + '.log')
        with path.open('w') as log:
            process = subprocess.Popen(args, cwd=cwd or self.checkout, env=self.environment,
                                       stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            try:
                code = process.wait(timeout=timeout)
            except BaseException:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
                raise
        require(code == 0, 'Failed stage ' + label + '; see ' + path.name)
        return path.read_text() if capture else None

    def compose(self, *args, timeout=120, label='compose'):
        return self.run([*self.base, *args], label, timeout=timeout, capture=True)

    def inspect(self, cid):
        template = '{{json .Config.Labels}}\n{{json .State}}\n{{json .Mounts}}\n{{json .NetworkSettings.Ports}}'
        parts = self.run(['docker', 'inspect', '--format', template, cid], 'inspect-' + cid[:12], capture=True).splitlines()
        labels, state, mounts, ports = map(json.loads, parts)
        return dict(Config=dict(Labels=labels), State=state, Mounts=mounts, NetworkSettings=dict(Ports=ports))

    def endpoints(self):
        urls = {}
        containers = {}
        for service in self.config['services']:
            cid = self.compose('ps', '-aq', service, label='id-' + service).strip()
            require(bool(cid), 'Missing container ' + service)
            details = self.inspect(cid)
            require(details['Config']['Labels']['com.docker.compose.project'] == self.project, 'Wrong container project')
            for mount in details['Mounts']:
                if mount['Type'] == 'volume':
                    require(mount['Name'] not in self.existing_volumes, 'Pre-existing volume reused')
                    declared = any(item.get('type') == 'volume' and item.get('source') and item['target'] == mount['Destination'] for item in self.config['services'][service].get('volumes', []))
                    require(not declared or mount['Name'].startswith(self.project + '_'), 'Volume not owned by isolated project')
                    self.created_volumes.add(mount['Name'])
            containers[service] = details
            for port, bindings in (details['NetworkSettings']['Ports'] or {}).items():
                for binding in bindings or []:
                    require(binding['HostIp'] == '127.0.0.1', 'Non-loopback port binding')
                    urls[service + ':' + port.split('/')[0]] = 'http://127.0.0.1:' + binding['HostPort']
        return urls, containers

    def smoke(self, urls):
        api = urls['fleet-api:8080'] + '/api/v1'
        ui = urls['fleet-dashboard:8080']
        require(json_http(api + '/dashboard')[2]['totalVehicles'] == 0, 'Expected fresh database')
        migrations = self.compose('exec', '-T', 'postgres', 'sh', '-ec',
            'exec psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"',
            'sh', 'SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank', label='migrations')
        require(migrations.splitlines() == ['1', '2', '3', '4', '5'], 'Flyway migrations missing')
        self.record('fresh database and automatic migrations', dict(versions=migrations.splitlines()))
        status, headers, vehicle = json_http(api + '/vehicles', dict(externalCode='CLEAN-CLONE', plate='CLONE070', serviceIntervalKm=15000, nextServiceAtKm=80000))
        require(status == 201 and '/vehicles/' + vehicle['id'] in headers.get('Location', ''), 'Create contract mismatch')
        require(json_http(api + '/vehicles?query=CLEAN-CLONE')[2]['totalElements'] == 1, 'Vehicle search mismatch')
        self.record('vehicle registration and search', dict(vehicleId=vehicle['id']))
        clock = self.compose('exec', '-T', 'telemetry-gateway', 'date', '+%s', label='gateway-clock').strip()
        observed = dt.datetime.fromtimestamp(int(clock), dt.timezone.utc)
        message = dict(protocolVersion=1, messageId=str(uuid.uuid4()), vehicleId=vehicle['id'], sequenceNumber=0,
                       observedAt=observed.isoformat(), speedKmh=72.4, engineTemperatureC=130,
                       batteryVoltage=12.6, odometerKm=85000, latitude=41.9028, longitude=12.4964)
        tcp = urllib.parse.urlsplit(urls['telemetry-gateway:7000'])
        ack = send_message(tcp.hostname, tcp.port, message)
        def persisted():
            status, _, body = http(api + '/vehicles/' + vehicle['id'] + '/state')
            return json.loads(body) if status == 200 else None
        state = wait(persisted, 'state persistence', 30)
        require(state['lastSequenceNumber'] == 0 and state['speedKmh'] == 72.4, 'State mismatch')
        interval = urllib.parse.urlencode({'from': (observed - dt.timedelta(seconds=1)).isoformat(), 'to': (observed + dt.timedelta(seconds=1)).isoformat()})
        history = json_http(api + '/vehicles/' + vehicle['id'] + '/telemetry?' + interval)[2]
        require(history['totalElements'] == 1 and history['content'][0]['messageId'] == message['messageId'], 'History mismatch')
        alerts = wait(lambda: (lambda page: page if page['totalElements'] == 2 else None)(json_http(api + '/alerts?vehicleId=' + vehicle['id'])[2]), 'alert persistence', 30)
        require({row['type'] for row in alerts['content']} == {'ENGINE_TEMPERATURE_HIGH', 'SERVICE_DUE'}, 'Alert types mismatch')
        require(all(row['sourceMessageId'] == message['messageId'] for row in alerts['content']), 'Alert correlation mismatch')
        replay = send_message(tcp.hostname, tcp.port, message)
        # Round-trip to the gateway/processor, then verify eventual SQL/API state through the marker.
        marker = {**message, 'messageId': str(uuid.uuid4()), 'sequenceNumber': 1, 'engineTemperatureC': 90, 'odometerKm': 85001}
        send_message(tcp.hostname, tcp.port, marker)
        wait(lambda: (lambda item: item and item['lastSequenceNumber'] == 1)(persisted()), 'replay drain', 30)
        require(json_http(api + '/vehicles/' + vehicle['id'] + '/telemetry?' + interval)[2]['totalElements'] == 2, 'Duplicate telemetry after replay')
        self.record('TCP to Kafka SQL state history and alerts', dict(messageId=message['messageId'], ack=ack['status'], replayAck=replay['status'], historyRowsAfterMarker=2, initialAlerts=2))
        projected = self.compose('exec', '-T', 'redis', 'redis-cli', '--raw', 'GET', 'vehicle:last:' + vehicle['id'], label='redis-state').strip()
        require(json.loads(projected)['lastSequenceNumber'] == 1, 'Redis projection missing')
        self.record('Redis projection', dict(lastSequenceNumber=1))
        for path in ['/', '/healthz', '/vehicles/' + vehicle['id'] + '/telemetry', '/alerts']:
            status, _, body = http(ui + path)
            require(status == 200 and (path == '/healthz' or b'<app-root' in body), 'Frontend SPA/health mismatch')
        require(json_http(ui + '/api/v1/dashboard')[2]['totalVehicles'] == 1, 'Frontend API proxy mismatch')
        self.record('frontend production routing health and real API proxy', dict(reachable=True))
        for name, path in [('prometheus:9090', '/-/ready'), ('grafana:3000', '/api/health')]:
            wait(lambda: http(urls[name] + path)[0] == 200, 'observability startup', 30)
        targets = wait(lambda: (lambda data: data if len(data) == 3 and all(item['health'] == 'up' for item in data) else None)(json_http(urls['prometheus:9090'] + '/api/v1/targets')[2]['data']['activeTargets']), 'Prometheus targets', 45)
        self.record('observability startup', dict(prometheusTargets=len(targets), grafanaHealthy=True))

    def cleanup(self):
        errors = []
        if self.deploy_attempted and self.private.exists():
            try:
                self.compose('down', '--volumes', '--remove-orphans', '--rmi', 'local', timeout=180, label='cleanup')
                for resource, args in [('containers', ['ps', '-aq']), ('networks', ['network', 'ls', '-q']), ('volumes', ['volume', 'ls', '-q'])]:
                    remaining = self.run(['docker', *args, '--filter', 'label=com.docker.compose.project=' + self.project], 'cleanup-' + resource, capture=True).strip()
                    require(not remaining, 'Resources not removed: ' + resource)
                volumes = set(self.run(['docker', 'volume', 'ls', '-q'], 'remaining-volumes', capture=True).splitlines())
                require(not (volumes & self.created_volumes), 'Anonymous volumes not removed')
            except Exception as error:
                errors.append(str(error))
        for path in [self.private, self.checkout / '.env']:
            path.unlink(missing_ok=True)
        require(not errors, '; '.join(errors))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--repository', default=None)
    parser.add_argument('--ref', default='main')
    args = parser.parse_args()
    verification = Verification(args.output)
    success = False
    try:
        repository = args.repository or subprocess.check_output(['git', 'remote', 'get-url', 'origin'], cwd=ROOT, text=True).strip()
        print('Cloning published source…', flush=True)
        verification.run(['git', 'clone', '--no-local', '--depth', '1', '--branch', args.ref, repository, str(verification.checkout)], 'clone', cwd=ROOT)
        commit = verification.run(['git', 'rev-parse', 'HEAD'], 'commit', capture=True).strip()
        require(not verification.run(['git', 'status', '--porcelain'], 'initial-status', capture=True).strip(), 'Clone not clean')
        for path in ['.env', 'frontend/fleet-dashboard/node_modules', 'frontend/fleet-dashboard/dist', 'services/fleet-api/target']:
            require(not (verification.checkout / path).exists(), 'Generated files present in clone')
        verification.record('fresh published clone', dict(commit=commit))
        print('Maven clean verify…', flush=True)
        verification.run(['./mvnw', '--batch-mode', '--no-transfer-progress', 'clean', 'verify'], 'maven', timeout=1200)
        verification.record('backend quality gate', java_results(verification.checkout))
        for directory in ['load', 'operations', 'e2e']:
            verification.run(['python3', '-m', 'unittest', 'discover', '-s', 'infrastructure/' + directory, '-p', 'test_*.py'], 'python-' + directory)
        verification.record('infrastructure unit tests', dict(groups=3))
        print('Frontend npm ci and quality gate…', flush=True)
        fe = verification.checkout / 'frontend/fleet-dashboard'
        verification.run(['npm', 'ci', '--no-audit', '--no-fund'], 'npm-ci', cwd=fe, timeout=300)
        verification.run(['npm', 'run', 'verify'], 'frontend', cwd=fe, timeout=300)
        verification.record('frontend quality gate', dict(coverage=json.loads((fe / 'coverage/fleet-dashboard/coverage-summary.json').read_text())['total']))
        example = verification.checkout / '.env.example'
        (verification.checkout / '.env').write_bytes(example.read_bytes())
        verification.environment = {**os.environ, **dict(line.split('=', 1) for line in example.read_text().splitlines() if line and not line.startswith('#'))}
        resolved = json.loads(verification.run(['docker', 'compose', '--env-file', '.env', 'config', '--format', 'json'], 'compose-resolve', capture=True))
        verification.config = isolated_config(resolved, verification.checkout)
        verification.private.write_text(json.dumps(verification.config))
        verification.private.chmod(0o600)
        # The resolved configuration is transient; it contains example credentials.
        (verification.output / 'compose-resolve.log').unlink()
        print('Building and starting isolated complete deployment…', flush=True)
        verification.existing_volumes = set(verification.run(['docker', 'volume', 'ls', '-q'], 'existing-volumes', capture=True).splitlines())
        verification.deploy_attempted = True
        verification.compose('up', '-d', '--build', timeout=900, label='startup')
        def ready():
            urls, containers = verification.endpoints()
            for name, details in containers.items():
                state = details['State']
                if name in ['flyway', 'kafka-init']:
                    if state['Status'] != 'exited': return None
                    require(state['ExitCode'] == 0, 'Initialization failed: ' + name)
                else:
                    require(state['Status'] not in ('exited', 'dead'), 'Service failed: ' + name)
                    if state['Status'] != 'running' or state.get('Health', {}).get('Status', 'healthy') != 'healthy': return None
            return urls
        urls = wait(ready, 'deployment healthy', 180)
        verification.record('complete deployment healthy', dict(project=verification.project, services=len(verification.config['services']), loopbackDynamicPorts=True))
        verification.smoke(urls)
        require(not verification.run(['git', 'status', '--porcelain'], 'final-status', capture=True).strip(), 'Tracked files changed during verification')
        verification.record('tracked checkout unchanged', dict(clean=True))
        success = True
    except BaseException as error:
        (verification.output / 'failure.json').write_text(json.dumps(dict(type=type(error).__name__, message=str(error)), indent=2) + '\n')
        print('FAILED:', str(error), flush=True)
    finally:
        try:
            verification.cleanup()
            verification.record('isolated resources and private configuration removed', dict(clean=True))
        except Exception as error:
            success = False
            print('CLEANUP FAILED:', str(error), flush=True)
        if success:
            (verification.output / 'summary.json').write_text(json.dumps(dict(result='passed', checks=len(verification.evidence), commit=commit), indent=2) + '\n')
            print('Complete clean-clone verification passed.', flush=True)
    raise SystemExit(0 if success else 1)


if __name__ == '__main__':
    def interrupted(signum, frame):
        raise KeyboardInterrupt('Interrupted')
    signal.signal(signal.SIGTERM, interrupted)
    main()
