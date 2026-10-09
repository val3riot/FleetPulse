#!/usr/bin/env python3
"""FP-054: verifica security baseline e telemetria del simulatore in Compose isolato."""
import argparse
import json
from pathlib import Path
import sys
import urllib.error
import urllib.request
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'e2e'))
from verify_nominal import APPS, Stack, command, require, wait


def response(url):
    try:
        with urllib.request.urlopen(url, timeout=5) as result:
            return result.status, result.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def available(url):
    try:
        return response(url)[0] == 200
    except OSError:
        return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    output = parser.parse_args().output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    stack = Stack(output)
    stack.project = 'fp054-' + uuid.uuid4().hex[:10]
    stack.base[3] = stack.project
    evidence = []

    def record(name, details):
        evidence.append(dict(check=name, details=details))
        (output / 'security-checks.json').write_text(json.dumps(evidence, indent=2))

    try:
        original = json.loads(command(['docker', 'compose', 'config', '--format', 'json']))
        ports = {name: json.loads(json.dumps(service.get('ports', [])))
                 for name, service in original['services'].items()}
        require(all(port.get('host_ip') == '127.0.0.1'
                    for mappings in ports.values() for port in mappings), 'Non-loopback host bind')
        record('Original Compose host bindings', ports)
        require(command(['git', 'check-ignore', '.env']).strip() == '.env', '.env is not ignored')
        require(not command(['git', 'ls-files', '.env']).strip(), '.env is tracked')
        record('Git environment exclusion', dict(ignored=True, tracked=False))
        print('Building isolated stack ' + stack.project, flush=True)
        stack.prepare()
        config = json.loads(stack.config.read_text())
        for name in ('vehicle-simulator', 'prometheus', 'grafana'):
            service = original['services'][name]
            service.pop('container_name', None)
            if 'build' in service:
                service.pop('image', None)
            service['restart'] = 'no'
            for port in service.get('ports', []):
                port['published'] = '0'
            config['services'][name] = service
        # Exercise the simulator as well as the synthetic nominal client.
        config['services']['vehicle-simulator']['environment']['SIMULATOR_ENABLED'] = 'true'
        config['services']['vehicle-simulator']['environment']['SIMULATOR_VEHICLE_COUNT'] = '1'
        stack.config.write_text(json.dumps(config))
        stack.call('up', '-d', '--build', 'vehicle-simulator', 'prometheus', 'grafana', timeout=900)
        for name in config['services']:
            cid = stack.call('ps', '-aq', name).strip()
            require(bool(cid), 'Missing container: ' + name)
            info = json.loads(command(['docker', 'inspect', cid]))[0]
            if name in ('flyway', 'kafka-init'):
                require(info['State']['Status'] == 'exited' and info['State']['ExitCode'] == 0,
                        'Initialization job failed: ' + name)
                user = info['Config']['User']
                require(bool(user) and user.split(':')[0] not in ('0', 'root'),
                        'Root initialization job: ' + name)
                # Check that the configured image user resolves to a real nonzero UID.
                uid = command(['docker', 'run', '--rm', '--network', 'none', '--user', user,
                               '--entrypoint', 'id', info['Image'], '-u']).strip()
                require(int(uid) != 0, 'Root job image user: ' + name)
                record('Non-root initialization: ' + name, dict(user=user, uid=int(uid), exitCode=0))
            else:
                uid = int(stack.call('exec', '-T', name, 'id', '-u').strip())
                status = stack.call('exec', '-T', name, 'cat', '/proc/1/status')
                process_uids = next(line.split()[1:] for line in status.splitlines()
                                    if line.startswith('Uid:'))
                require(uid != 0 and all(int(value) != 0 for value in process_uids),
                        'Root container process: ' + name)
                require(not info['HostConfig']['Privileged'], 'Privileged container: ' + name)
                record('Non-root runtime: ' + name, dict(execUid=uid, pid1Uids=process_uids))
            for bindings in (info['NetworkSettings']['Ports'] or {}).values():
                require(all(b['HostIp'] == '127.0.0.1' for b in bindings or []),
                        'Runtime non-loopback binding: ' + name)
        for app in APPS:
            url = stack.urls[app]
            status, body = response(url + '/actuator')
            require(status == 200, 'Actuator discovery unavailable')
            links = set(json.loads(body)['_links'])
            require(links == {'self', 'health', 'health-path', 'prometheus'},
                    'Unexpected exposed endpoint: ' + app)
            for endpoint in ('health', 'health/liveness', 'health/readiness'):
                status, body = response(url + '/actuator/' + endpoint)
                health = json.loads(body)
                require(status == 200 and health.get('status') == 'UP'
                        and set(health) <= {'status', 'groups'}
                        and ('groups' not in health or set(health['groups']) == {'liveness', 'readiness'}),
                        'Health leaks details or is unavailable: ' + app)
            require(response(url + '/actuator/prometheus')[0] == 200, 'Scrape unavailable')
            blocked = ('info', 'env', 'configprops', 'beans', 'mappings', 'metrics',
                       'loggers', 'heapdump', 'threaddump', 'shutdown')
            for endpoint in blocked:
                require(response(url + '/actuator/' + endpoint)[0] == 404,
                        'Unexpected exposed endpoint: ' + endpoint)
            record('Minimal Actuator: ' + app, dict(links=sorted(links), absent=list(blocked)))
        prometheus = 'http://' + stack.call('port', 'prometheus', '9090').strip()
        grafana = 'http://' + stack.call('port', 'grafana', '3000').strip()
        wait(lambda: available(grafana + '/api/health'), 'Grafana unavailable', timeout=60)
        status, _ = response(grafana + '/api/datasources')
        require(status == 401, 'Grafana datasource administration accessible without authentication')
        def targets_ready():
            if not available(prometheus + '/-/ready'):
                return False
            targets = json.loads(response(prometheus + '/api/v1/targets')[1])['data']['activeTargets']
            return (len(targets) == 3 and all(t['health'] == 'up' for t in targets)
                    and {t['labels']['job'] for t in targets} == set(APPS))
        wait(targets_ready, 'Prometheus targets unavailable', timeout=60)
        record('Monitoring', dict(prometheusTargets=3, grafanaAnonymousDatasourceStatus=status))
        wait(lambda: int(stack.sql('SELECT count(*) FROM telemetry_samples')) > 0,
             'Simulator did not persist telemetry', timeout=60)
        # Stop the producer before exact global topic reconciliation in Nominal.run().
        stack.call('stop', 'vehicle-simulator')
        wait(lambda: stack.lag()['total'] == 0, 'Simulator lag not drained')
        record('Simulator telemetry', dict(persisted=True))
        # Nominal has exact global record-count assertions: use a separate isolated run below.
        (output / 'summary.json').write_text(json.dumps(dict(result='passed',
            checks=len(evidence), project=stack.project), indent=2))
    except BaseException as error:
        (output / 'failure.json').write_text(json.dumps(dict(errorType=type(error).__name__,
            error=str(error) if isinstance(error, AssertionError) else 'Operation failed')))
        raise
    finally:
        stack.close()
        remaining = {kind: command(['docker', kind, 'ls', *(['-a'] if kind == 'container' else []),
            '-q', '--filter', 'label=com.docker.compose.project=' + stack.project]).splitlines()
            for kind in ('container', 'network', 'volume')}
        (output / 'cleanup.json').write_text(json.dumps(remaining, indent=2))
        require(not any(remaining.values()), 'Isolated resources remain')
    print(f'Passed: {len(evidence)} security checks; isolated resources removed', flush=True)


if __name__ == '__main__':
    main()
