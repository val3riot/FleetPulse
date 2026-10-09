#!/usr/bin/env python3
"""Diagnostica FleetPulse di sola lettura, con configurazione Compose risolta."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
APPS = ('fleet-api', 'telemetry-gateway', 'telemetry-processor')


def run(args, timeout=30):
    result = subprocess.run(args, cwd=ROOT, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        # Non riportare stderr/configurazione: possono contenere credenziali o payload.
        raise RuntimeError(f'Comando fallito (exit {result.returncode}); controllare stato e log del servizio')
    return result.stdout


def identifier(value):
    return str(uuid.UUID(value))


def nonnegative(value):
    number = int(value)
    if number < 0:
        raise argparse.ArgumentTypeError('Richiesto intero >= 0')
    return number


def flatten(value, prefix=''):
    result = {}
    for key, item in value.items():
        name = prefix + key
        if isinstance(item, dict):
            result.update(flatten(item, name + '.'))
        else:
            result[name] = item
    return result


class Inspector:
    def __init__(self, base):
        self.base = base
        self.config = json.loads(self.call('config', '--format', 'json'))
        self.env = self.config['services']['telemetry-processor']['environment']

    def call(self, *args, timeout=30):
        return run([*self.base, *args], timeout)

    def kafka(self, tool, *args, timeout=30):
        return self.call('exec', '-T', 'kafka', '/opt/kafka/bin/' + tool,
                         '--bootstrap-server', 'kafka:19092', *args, timeout=timeout)

    def database(self, message):
        # UUID normalizzato prima dell'interpolazione; sessione read-only con deadline SQL.
        query = f"""BEGIN READ ONLY; SET LOCAL statement_timeout='5s';
        SELECT message_id, vehicle_id, sequence_number, observed_at, received_at, processed_at
        FROM telemetry_samples WHERE message_id='{identifier(message)}';
        SELECT id, vehicle_id, source_message_id, type, status FROM maintenance_alerts
        WHERE source_message_id='{identifier(message)}'; COMMIT;"""
        return self.call('exec', '-T', 'postgres', 'sh', '-ec',
                         'exec psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"',
                         'sh', query)

    def redis(self, vehicle):
        key = 'vehicle:last:' + identifier(vehicle)
        return {operation: self.call('exec', '-T', 'redis', 'redis-cli', '--raw', operation,
                                    *([] if operation == 'PING' else [key])).strip()
                for operation in ('PING', 'GET', 'TTL')}

    def logs(self, message, since, tail):
        text = self.call('logs', '--no-color', '--no-log-prefix', '--since', since,
                         '--tail', str(tail), 'telemetry-gateway', 'telemetry-processor')
        matches, text_matches = [], []
        for line in text.splitlines():
            try:
                entry = flatten(json.loads(line))
            except (ValueError, AttributeError):
                if message in line:
                    text_matches.append(line)
                continue
            if entry.get('messageId') == message:
                matches.append({key: value for key, value in entry.items()
                                if key in ('@timestamp', 'service.name', 'event.action', 'messageId',
                                           'vehicleId', 'sequenceNumber', 'topic', 'partition', 'offset',
                                           'deliveryAttempt', 'errorType', 'reason', 'sourceTopic',
                                           'sourcePartition', 'sourceOffset') or key.startswith('kafka.')})
        # Il testo può includere payload: segnalare i match senza ristamparli.
        return dict(events=matches, nonJsonMatches=len(text_matches),
                    note='Ricerca limitata per servizio; assenza non prova perdita. Per log testuali usare ricerca locale protetta.')

    def records(self, kind, partition, offset, count):
        topic = self.env[{'raw': 'KAFKA_TOPIC_RAW', 'rejected': 'KAFKA_TOPIC_REJECTED',
                          'dlt': 'KAFKA_TOPIC_DEAD_LETTER'}[kind]]
        text = self.kafka('kafka-console-consumer.sh', '--topic', topic, '--partition', str(partition),
                          '--offset', str(offset), '--max-messages', str(count), '--timeout-ms', '5000',
                          '--consumer-property', 'enable.auto.commit=false', timeout=20)
        summaries = []
        for line in text.splitlines():
            try:
                event = json.loads(line)
                original = event.get('originalPayload') or {}
                summaries.append({key: event[key] for key in ('messageId', 'vehicleId', 'reason',
                    'sourceTopic', 'sourcePartition', 'sourceOffset', 'attempts', 'errorCode') if key in event}
                    | {'originalMessageId': original.get('messageId') if isinstance(original, dict) else None})
            except (ValueError, AttributeError):
                summaries.append({'unreadableRecord': True})
        return dict(topic=topic, partition=partition, requestedOffset=offset, records=summaries,
                    note='Finestra limitata; consultare earliest/latest. Nessun offset del group applicativo modificato.')

    def health(self):
        result = {}
        for app in APPS:
            address = self.call('port', app, '8080').strip()
            probes = {}
            for group in ('liveness', 'readiness'):
                try:
                    with urllib.request.urlopen('http://' + address + '/actuator/health/' + group,
                                                timeout=5) as response:
                        probes[group] = {'http': response.status, 'body': json.load(response)}
                except urllib.error.HTTPError as error:
                    probes[group] = {'http': error.code, 'body': json.load(error)}
                except (OSError, ValueError):
                    probes[group] = {'unreachable': True}
            result[app] = probes
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', help='Nome progetto Compose, se non quello predefinito')
    parser.add_argument('--file', type=Path, default=ROOT / 'compose.yaml')
    sub = parser.add_subparsers(dest='operation', required=True)
    for name in ('health', 'status', 'kafka'):
        sub.add_parser(name)
    sql = sub.add_parser('database')
    sql.add_argument('message_id', type=identifier)
    redis = sub.add_parser('redis')
    redis.add_argument('vehicle_id', type=identifier)
    logs = sub.add_parser('logs')
    logs.add_argument('message_id', type=identifier)
    logs.add_argument('--since', default='30m')
    logs.add_argument('--tail', type=int, choices=range(1, 10001), default=2000)
    records = sub.add_parser('records')
    records.add_argument('topic', choices=('raw', 'rejected', 'dlt'))
    records.add_argument('--partition', type=nonnegative, required=True)
    records.add_argument('--offset', type=nonnegative, required=True)
    records.add_argument('--count', type=int, choices=range(1, 101), default=20)
    args = parser.parse_args()
    base = ['docker', 'compose', '-f', str(args.file.resolve())]
    if args.project:
        base += ['-p', args.project]
    try:
        inspector = Inspector(base)
        if args.operation == 'database':
            print(inspector.database(args.message_id))
        elif args.operation == 'redis':
            print(json.dumps(inspector.redis(args.vehicle_id), indent=2))
        elif args.operation == 'logs':
            print(json.dumps(inspector.logs(args.message_id, args.since, args.tail), indent=2))
        elif args.operation == 'records':
            print(json.dumps(inspector.records(args.topic, args.partition, args.offset, args.count), indent=2))
        elif args.operation == 'health':
            print(json.dumps(inspector.health(), indent=2))
        elif args.operation == 'status':
            print(inspector.call('ps', '--all'))
        elif args.operation == 'kafka':
            print(inspector.kafka('kafka-consumer-groups.sh', '--describe', '--group',
                                  inspector.env['KAFKA_CONSUMER_GROUP_ID']))
            for key in ('KAFKA_TOPIC_RAW', 'KAFKA_TOPIC_REJECTED', 'KAFKA_TOPIC_DEAD_LETTER'):
                topic = inspector.env[key]
                print(inspector.kafka('kafka-topics.sh', '--describe', '--topic', topic))
                for bound in ('-2', '-1'):
                    print('earliest' if bound == '-2' else 'latest')
                    print(inspector.kafka('kafka-get-offsets.sh', '--topic', topic, '--time', bound))
    except (RuntimeError, subprocess.TimeoutExpired, OSError) as error:
        print(str(error) if isinstance(error, RuntimeError) else 'Operazione non disponibile o timeout', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
