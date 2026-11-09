#!/usr/bin/env python3
"""Demonstrate the real telemetry flow and processor recovery in an isolated stack."""
import argparse
import datetime as dt
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import urllib.parse
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'infrastructure/hardening'))
spec = importlib.util.spec_from_file_location('container_verification', ROOT / 'infrastructure/hardening/verify.py')
containers = importlib.util.module_from_spec(spec)
spec.loader.exec_module(containers)
stack_tools = containers.clean_clone
require = stack_tools.require


def instant(value):
    return dt.datetime.fromisoformat(value.replace('Z', '+00:00'))


def compare_state(actual, message):
    for key in ('vehicleId', 'speedKmh', 'engineTemperatureC', 'batteryVoltage', 'odometerKm', 'latitude', 'longitude'):
        require(actual.get(key) == message[key], 'State mismatch: ' + key)
    require(actual.get('lastSequenceNumber') == message['sequenceNumber'], 'State sequence mismatch')
    require(instant(actual['lastSeenAt']) == instant(message['observedAt']), 'State timestamp mismatch')


def topic_ends(text, topic):
    rows = [line.split(':') for line in text.splitlines() if line.startswith(topic + ':')]
    require(len(rows) == 3 and all(len(row) == 3 and row[1].isdigit() and row[2].isdigit() for row in rows), 'Topic offsets unavailable')
    offsets = {int(row[1]): int(row[2]) for row in rows}
    require(set(offsets) == {0, 1, 2}, 'Topic partitions missing or repeated')
    return offsets


def consumer_lag(text, group, topic, ends):
    lines = [line.split() for line in text.splitlines()]
    headers = [line for line in lines if line and line[0] == 'GROUP' and 'LOG-END-OFFSET' in line]
    require(len(headers) == 1, 'Consumer group table missing')
    header = headers[0]
    columns = {key: header.index(key) for key in ('GROUP', 'TOPIC', 'PARTITION', 'CURRENT-OFFSET', 'LOG-END-OFFSET')}
    rows = [line for line in lines if len(line) > max(columns.values())
            and line[columns['GROUP']] == group and line[columns['TOPIC']] == topic]
    partitions = [line[columns['PARTITION']] for line in rows]
    require(len(set(partitions)) == len(partitions) and set(partitions) <= {'0', '1', '2'}, 'Invalid consumer partitions')
    require(set(ends) == {0, 1, 2}, 'Broker partition offsets incomplete')
    # An inactive group omits partitions that have never committed an offset.
    require(all(end == 0 for partition, end in ends.items() if str(partition) not in partitions), 'Nonempty partition missing from consumer group')
    total = 0
    for row in rows:
        current, end = row[columns['CURRENT-OFFSET']], row[columns['LOG-END-OFFSET']]
        require(end.isdigit() and (current.isdigit() or current == '-'), 'Consumer offsets unavailable')
        offset = int(current) if current != '-' else 0
        require(int(end) == ends[int(row[columns['PARTITION']])], 'Broker and group log end mismatch')
        require(int(end) >= offset, 'Consumer offset exceeds log end')
        total += int(end) - offset
    return total


class Demo:
    def __init__(self, verification, environment, interactive=False):
        self.stack = verification
        self.environment = environment
        self.interactive = interactive
        self.messages = []
        self.links = {}
        self.vehicle_id = None
        self.alert_id = None
        self.urls = {}

    def record(self, name, details):
        self.stack.record(name, details)

    def checkpoint(self, phase, description):
        interval = None
        if self.messages:
            interval = {'from': (instant(self.messages[0]['observedAt']) - dt.timedelta(minutes=1)).strftime('%Y-%m-%d %H:%M:%S'),
                        'to': (instant(self.messages[-1]['observedAt']) + dt.timedelta(minutes=1)).strftime('%Y-%m-%d %H:%M:%S')}
        session = dict(phase=phase, project=self.stack.project, vehicleId=self.vehicle_id,
                       alertId=self.alert_id, links=self.links, resources='temporary', linksActive=True, historyInterval=interval)
        (self.stack.output / 'session.json').write_text(json.dumps(session, indent=2) + '\n')
        print('\n' + description, flush=True)
        for name, url in self.links.items():
            print('  ' + name + ': ' + url, flush=True)
        if interval:
            print('  Storico: da ' + interval['from'] + ' a ' + interval['to'], flush=True)
        if self.interactive:
            input('Premi Invio per continuare; Ctrl+C termina e rimuove la demo. ')

    def api(self, path, body=None):
        return stack_tools.json_http(self.urls['fleet-api:8080'] + '/api/v1' + path, body)

    def clock(self):
        value = self.stack.compose('exec', '-T', 'telemetry-gateway', 'date', '+%s', label='gateway-clock')
        return dt.datetime.fromtimestamp(int(value.strip()), dt.timezone.utc)

    def message(self, sequence, temperature):
        return dict(protocolVersion=1, messageId=str(uuid.uuid4()), vehicleId=self.vehicle_id,
                    sequenceNumber=sequence, observedAt=self.clock().isoformat(), speedKmh=60 + sequence * 10,
                    engineTemperatureC=temperature,
                    batteryVoltage=float(self.environment['TELEMETRY_ALERT_MINIMUM_BATTERY_VOLTAGE']) + 1,
                    odometerKm=10000 + sequence, latitude=41.9028, longitude=12.4964)

    def send(self, message):
        tcp = urllib.parse.urlsplit(self.urls['telemetry-gateway:7000'])
        ack = stack_tools.send_message(tcp.hostname, tcp.port, message)
        require(ack.get('protocolVersion') == 1, 'ACK protocol version mismatch')
        self.messages.append(message)
        self.record('TCP accepted sequence ' + str(message['sequenceNumber']), dict(messageId=message['messageId'], ack=ack))

    def state(self):
        return self.api('/vehicles/' + self.vehicle_id + '/state')[2]

    def await_state(self, message):
        def matches():
            status, _, raw = stack_tools.http(self.urls['fleet-api:8080'] + '/api/v1/vehicles/' + self.vehicle_id + '/state')
            if status != 200:
                return None
            state = json.loads(raw)
            if state.get('lastSequenceNumber') != message['sequenceNumber']:
                return None
            compare_state(state, message)
            return state
        return stack_tools.wait(matches, 'telemetry state persisted', 45)

    def history(self):
        interval = urllib.parse.urlencode({'from': (instant(self.messages[0]['observedAt']) - dt.timedelta(seconds=1)).isoformat(),
                                          'to': (self.clock() + dt.timedelta(seconds=1)).isoformat(), 'size': 10})
        return self.api('/vehicles/' + self.vehicle_id + '/telemetry?' + interval)[2]

    def assert_history(self, messages):
        page = self.history()
        require(page['totalElements'] == len(messages), 'Unexpected telemetry count')
        rows = {row['messageId']: row for row in page['content']}
        require(set(rows) == {message['messageId'] for message in messages}, 'History message identity mismatch')
        for message in messages:
            row = rows[message['messageId']]
            for key in ('vehicleId', 'sequenceNumber', 'speedKmh', 'engineTemperatureC', 'odometerKm'):
                require(row[key] == message[key], 'History mismatch: ' + key)
        return page

    def alerts(self):
        return self.api('/alerts?vehicleId=' + self.vehicle_id)[2]

    def lag(self):
        text = self.stack.compose('exec', '-T', 'kafka', '/opt/kafka/bin/kafka-consumer-groups.sh',
                                  '--bootstrap-server', 'kafka:19092', '--describe', '--group',
                                  self.environment['KAFKA_CONSUMER_GROUP_ID'], label='consumer-lag')
        topic = self.environment['KAFKA_TOPIC_RAW']
        raw = self.stack.compose('exec', '-T', 'kafka', '/opt/kafka/bin/kafka-get-offsets.sh',
                                 '--bootstrap-server', 'kafka:19092', '--topic', topic, '--time', '-1', label='topic-offsets')
        return consumer_lag(text, self.environment['KAFKA_CONSUMER_GROUP_ID'], topic, topic_ends(raw, topic))

    def own_processor(self):
        cid = self.stack.compose('ps', '-aq', 'telemetry-processor', label='processor-id').strip()
        info = self.stack.inspect(cid)
        require(info['Config']['Labels']['com.docker.compose.project'] == self.stack.project, 'Processor outside demo project')
        return cid, info

    def run(self):
        require(self.api('/dashboard')[2]['totalVehicles'] == 0, 'Demo database is not empty')
        code, _, vehicle = self.api('/vehicles', dict(externalCode='DEMO-' + uuid.uuid4().hex[:8],
                                                     plate='DEMO073', serviceIntervalKm=15000, nextServiceAtKm=25000))
        require(code == 201 and vehicle['status'] == 'ACTIVE', 'Vehicle registration failed')
        self.vehicle_id = str(uuid.UUID(vehicle['id']))
        require(self.api('/vehicles/' + self.vehicle_id)[2]['id'] == self.vehicle_id, 'Vehicle detail mismatch')
        ui = self.urls['fleet-dashboard:8080']
        self.links = dict(dashboard=ui + '/dashboard', vehicle=ui + '/vehicles/' + self.vehicle_id,
                          history=ui + '/vehicles/' + self.vehicle_id + '/telemetry', alerts=ui + '/alerts')
        maximum = float(self.environment['TELEMETRY_ALERT_MAXIMUM_ENGINE_TEMPERATURE_C'])
        nominal = self.message(0, maximum - 10)
        self.send(nominal)
        self.await_state(nominal)
        self.assert_history([nominal])
        require(self.alerts()['totalElements'] == 0, 'Nominal telemetry generated an alert')
        self.record('Vehicle registration and nominal telemetry', dict(vehicleId=self.vehicle_id, sequence=0, historyRows=1, alerts=0))
        self.checkpoint('nominal', 'Veicolo registrato e prima telemetria visibile. I link esistono solo durante questa esecuzione.')

        hot = self.message(1, maximum + 10)
        self.send(hot)
        self.await_state(hot)
        def one_alert():
            page = self.alerts()
            return page if page['totalElements'] == 1 else None
        page = stack_tools.wait(one_alert, 'temperature alert created', 30)
        alert = page['content'][0]
        require(alert['vehicleId'] == self.vehicle_id and alert['sourceMessageId'] == hot['messageId']
                and alert['type'] == 'ENGINE_TEMPERATURE_HIGH' and alert['status'] == 'OPEN', 'Temperature alert mismatch')
        self.alert_id = str(uuid.UUID(alert['id']))
        self.links['alert'] = ui + '/alerts/' + self.alert_id
        self.assert_history(self.messages)
        stack_tools.wait(lambda: self.lag() == 0, 'consumer drained before outage', 45)
        self.record('Temperature alert and current state', dict(alertId=self.alert_id, sourceMessageId=hot['messageId'], sequence=1))
        self.checkpoint('alert', 'Temperatura alta: verifica stato, storico e dettaglio alert prima del guasto.')

        cid, _ = self.own_processor()
        self.stack.compose('stop', '-t', '10', 'telemetry-processor', timeout=40, label='processor-stop')
        stopped_id, stopped = self.own_processor()
        require(stopped_id == cid and stopped['State']['Status'] == 'exited', 'Processor did not stop')
        queued = self.message(2, maximum - 10)
        self.send(queued)
        require(self.lag() == 1, 'Expected one queued Kafka message')
        compare_state(self.state(), hot)
        self.assert_history(self.messages[:2])
        require(self.alerts()['totalElements'] == 1, 'Alert changed during outage')
        self.record('Controlled processor outage', dict(acceptedSequence=2, visibleSequence=1, historyRows=2, kafkaLag=1))
        self.checkpoint('processor-stopped', 'Processor fermo solo nella demo: Kafka ha accettato il messaggio, la UI conserva la sequenza precedente.')

        # Recover the same consumer/container without resending the accepted message.
        self.own_processor()
        self.stack.compose('start', 'telemetry-processor', timeout=60, label='processor-start')
        self.urls = stack_tools.wait(lambda: containers.ready(self.stack), 'recovered processor healthy', 180)
        require(self.own_processor()[0] == cid, 'Processor was recreated during recovery')
        self.await_state(queued)
        stack_tools.wait(lambda: self.lag() == 0, 'backlog consumed after recovery', 45)
        self.assert_history(self.messages)
        require(len(self.messages) == 3, 'Unexpected telemetry retransmission')
        page = self.alerts()
        require(page['totalElements'] == 1 and page['content'][0]['id'] == self.alert_id, 'Alert not preserved during recovery')
        require(self.api('/dashboard')[2]['totalVehicles'] == 1, 'Dashboard vehicle count mismatch')
        for url in self.links.values():
            status, _, body = stack_tools.http(url)
            require(status == 200 and b'<app-root' in body, 'Frontend deep link unavailable')
        proxy = stack_tools.json_http(ui + '/api/v1/vehicles/' + self.vehicle_id + '/state')[2]
        compare_state(proxy, queued)
        self.record('Recovery without retransmission', dict(sequence=2, historyRows=3, alerts=1, kafkaLag=0, sameProcessorContainer=True))
        self.checkpoint('recovered', 'Recupero completato: sequenza 2, tre campioni, alert conservato e coda vuota. Invio termina e rimuove le risorse.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--interactive', action='store_true', help='Pause at each dashboard checkpoint')
    args = parser.parse_args()
    verification = stack_tools.Verification(args.output)
    success = False
    demo = None
    try:
        fingerprint = containers.snapshot(verification.checkout)
        verification.record('Isolated source snapshot', dict(sha256=fingerprint,
            baseCommit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()))
        example = dict(line.split('=', 1) for line in (verification.checkout / '.env.example').read_text().splitlines() if line and not line.startswith('#'))
        verification.environment = {key: value for key, value in os.environ.items() if not key.startswith(('COMPOSE_', 'TRIVY_'))}
        verification.environment.update(example)
        original = json.loads(verification.run(['docker', 'compose', '-f', 'compose.yaml', '--env-file', '.env.example', 'config', '--format', 'json'], 'resolved', capture=True))
        require(original['services']['vehicle-simulator']['environment']['SIMULATOR_ENABLED'] == 'false', 'Demo requires exclusive synthetic traffic')
        verification.config = stack_tools.isolated_config(original, verification.checkout)
        verification.private.write_text(json.dumps(verification.config))
        verification.private.chmod(0o600)
        (verification.output / 'resolved.log').unlink()
        verification.existing_volumes = set(verification.run(['docker', 'volume', 'ls', '-q'], 'existing-volumes', capture=True).splitlines())
        verification.deploy_attempted = True
        print('Avvio dello stack isolato ' + verification.project + ' con database nuovo…', flush=True)
        verification.compose('up', '-d', '--build', timeout=1200, label='startup')
        urls = stack_tools.wait(lambda: containers.ready(verification), 'demo startup healthy', 180)
        verification.record('Fresh complete deployment healthy', dict(project=verification.project))
        demo = Demo(verification, example, args.interactive)
        demo.urls = urls
        demo.run()
        success = True
    except BaseException as error:
        (verification.output / 'failure.json').write_text(json.dumps(dict(type=type(error).__name__, message=str(error)), indent=2) + '\n')
        print('DEMO FAILED: ' + str(error), flush=True)
    finally:
        try:
            verification.cleanup()
            verification.record('Isolated resources and private configuration removed', dict(clean=True))
            session = verification.output / 'session.json'
            if session.exists():
                value = json.loads(session.read_text())
                value.update(resources='removed', linksActive=False)
                session.write_text(json.dumps(value, indent=2) + '\n')
        except Exception as error:
            success = False
            (verification.output / 'cleanup-failure.json').write_text(json.dumps(dict(type=type(error).__name__, message=str(error)), indent=2) + '\n')
            print('CLEANUP FAILED: ' + str(error), flush=True)
        if success:
            (verification.output / 'summary.json').write_text(json.dumps(dict(result='passed', checks=len(verification.evidence),
                sourceSha256=fingerprint, vehicleId=demo.vehicle_id, alertId=demo.alert_id, messages=3,
                historyRows=3, finalSequence=2, alerts=1, finalKafkaLag=0, resourcesRemoved=True), indent=2) + '\n')
            print('Demo completata; risorse isolate rimosse.', flush=True)
    return 0 if success else 1


if __name__ == '__main__':
    def interrupted(signum, frame):
        raise KeyboardInterrupt('Interrupted')
    signal.signal(signal.SIGTERM, interrupted)
    sys.exit(main())
