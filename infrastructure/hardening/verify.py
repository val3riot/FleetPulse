#!/usr/bin/env python3
"""Build and inspect an isolated snapshot, exercise its APIs and scan exact images."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys

from policy import APPS, MAX_MIB, require, scan_result, validate_config, validate_runtime_budget

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('clean_clone', ROOT / 'infrastructure/clean-clone/verify.py')
clean_clone = importlib.util.module_from_spec(spec)
spec.loader.exec_module(clean_clone)


def snapshot(destination):
    files = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=ROOT).decode().split('\0')
    digest = hashlib.sha256()
    for name in sorted(set(filter(None, files))):
        source = ROOT / name
        require(clean_clone.inside(source, ROOT), 'Source outside repository')
        if not source.is_file():
            continue
        require((not source.name.startswith('.env') or source.name == '.env.example')
                and not name.startswith('tmp/'), 'Private source included')
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        digest.update(name.encode() + b'\0' + source.read_bytes() + b'\0')
    return digest.hexdigest()


def ready(verification):
    urls, containers = verification.endpoints()
    for name, details in containers.items():
        state = details['State']
        if name in ('flyway', 'kafka-init'):
            if state['Status'] != 'exited':
                return None
            require(state['ExitCode'] == 0, 'Initialization failed: ' + name)
        else:
            require(state['Status'] not in ('dead', 'exited'), 'Service failed: ' + name)
            if state['Status'] != 'running' or state.get('Health', {}).get('Status', 'healthy') != 'healthy':
                return None
    return urls


def inventory(verification):
    images = {}
    rows = []
    for name, service in verification.config['services'].items():
        cid = verification.compose('ps', '-aq', name, label='container-' + name).strip()
        template = '{{.Image}}\n{{.Config.User}}\n{{json .HostConfig}}'
        image_id, user, host = verification.run(['docker', 'inspect', '--format', template, cid], 'runtime-' + name, capture=True).splitlines()
        host = json.loads(host)
        validate_runtime_budget(name, service, host)
        if name in ('flyway', 'kafka-init'):
            require(user and user.split(':')[0] not in ('0', 'root'), 'Root initialization job: ' + name)
            uid = verification.run(['docker', 'run', '--rm', '--network', 'none', '--user', user,
                                    '--entrypoint', 'id', image_id, '-u'], 'job-uid-' + name, capture=True).strip()
            uids = [int(uid)]
        else:
            status = verification.compose('exec', '-T', name, 'cat', '/proc/1/status', label='pid1-' + name)
            uids = [int(value) for value in next(line for line in status.splitlines() if line.startswith('Uid:')).split()[1:]]
        require(uids and all(uid > 0 for uid in uids), 'Root runtime: ' + name)
        image = json.loads(verification.run(['docker', 'image', 'inspect', '--format', '{{json .}}', image_id], 'image-' + name, capture=True))
        size = image['Size']
        layers = len(image['RootFS']['Layers'])
        require(size <= MAX_MIB[name] * 1024**2 and layers <= 20, 'Image size/layer budget exceeded: ' + name)
        row = dict(service=name, imageId=image_id, configuredUser=user, pid1Uids=uids,
                   memoryBytes=host['Memory'], nanoCpus=host['NanoCpus'], sizeBytes=size,
                   layerCount=layers, repoDigests=image.get('RepoDigests', []))
        rows.append(row)
        images.setdefault(image_id, []).append(name)
    (verification.output / 'images.json').write_text(json.dumps(rows, indent=2) + '\n')
    verification.record('non-root runtime jobs resource and image budgets', dict(services=len(rows)))
    return images


def scans(verification, scanner, images):
    metadata = json.loads(verification.run([scanner, '--version', '--format', 'json'], 'scanner-version', capture=True))
    summaries = []
    for image_id, services in images.items():
        name = services[0]
        output = verification.output / ('scan-' + name + '.json')
        verification.run([scanner, '--config', os.devnull, 'image', '--image-src', 'docker', '--scanners', 'vuln', '--quiet',
                          '--ignorefile', os.devnull, '--ignore-unfixed=false',
                          '--severity', 'UNKNOWN,LOW,MEDIUM,HIGH,CRITICAL',
                          '--format', 'json', '--output', str(output), image_id], 'scanner-' + name, timeout=600)
        report = json.loads(output.read_text())
        result = scan_result(report, image_id, osless_go_image=services == ['prometheus'])
        if any(service in APPS - {'fleet-dashboard'} for service in services):
            require(any(row.get('Class') == 'lang-pkgs' and row.get('Type') == 'jar' for row in report['Results']), 'Java dependency scan missing')
        summaries.append(dict(services=services, imageId=image_id, scannedAt=report['CreatedAt'], **result))
        print('SCAN ' + name + ': ' + json.dumps(result['counts']), flush=True)
    # Record database metadata after the scan, including any initial download/update.
    metadata = json.loads(verification.run([scanner, '--version', '--format', 'json'], 'scanner-version', capture=True))
    (verification.output / 'scan-summary.json').write_text(json.dumps(dict(scanner=metadata, images=summaries), indent=2) + '\n')
    require(all(row['passes'] for row in summaries), 'High/Critical vulnerabilities remain; see scan-summary.json')
    verification.record('runtime OS and application vulnerability policy', dict(images=len(summaries), high=0, critical=0))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--scanner', default='trivy', help='Trivy executable; no installation is performed')
    args = parser.parse_args()
    scanner = shutil.which(args.scanner)
    require(scanner is not None, 'Install Trivy or provide --scanner /path/to/trivy')
    scanner = str(Path(scanner).resolve())
    verification = clean_clone.Verification(args.output)
    success = False
    try:
        source_hash = snapshot(verification.checkout)
        verification.record('isolated working source snapshot', dict(sha256=source_hash,
            baseCommit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()))
        example = dict(line.split('=', 1) for line in (verification.checkout / '.env.example').read_text().splitlines() if line and not line.startswith('#'))
        verification.environment = {key: value for key, value in os.environ.items() if not key.startswith(('COMPOSE_', 'TRIVY_'))}
        verification.environment.update(example)
        original = json.loads(verification.run(['docker', 'compose', '-f', 'compose.yaml', '--env-file', '.env.example', 'config', '--format', 'json'], 'resolved', capture=True))
        dockerfiles = {name: (verification.checkout / service['build'].get('dockerfile', 'Dockerfile')).read_text()
                       if service['build']['context'] == str(verification.checkout) else
                       (Path(service['build']['context']) / service['build'].get('dockerfile', 'Dockerfile')).read_text()
                       for name, service in original['services'].items() if 'build' in service}
        validate_config(original, dockerfiles)
        (verification.output / 'resolved.log').unlink()
        verification.record('immutable base images and bounded local configuration', dict(services=len(original['services'])))
        verification.config = clean_clone.isolated_config(original, verification.checkout)
        verification.private.write_text(json.dumps(verification.config))
        verification.private.chmod(0o600)
        verification.existing_volumes = set(verification.run(['docker', 'volume', 'ls', '-q'], 'existing-volumes', capture=True).splitlines())
        verification.deploy_attempted = True
        print('Building isolated deployment…', flush=True)
        verification.compose('up', '-d', '--build', timeout=1200, label='startup')
        urls = clean_clone.wait(lambda: ready(verification), 'healthy deployment', 180)
        verification.record('complete deployment healthy', dict(project=verification.project))
        images = inventory(verification)
        verification.smoke(urls)
        scans(verification, scanner, images)
        success = True
    except BaseException as error:
        (verification.output / 'failure.json').write_text(json.dumps(dict(type=type(error).__name__, message=str(error)), indent=2) + '\n')
        print('FAILED: ' + str(error), flush=True)
    finally:
        try:
            verification.cleanup()
            verification.record('isolated resources and private configuration removed', dict(clean=True))
        except Exception as error:
            success = False
            print('CLEANUP FAILED: ' + str(error), flush=True)
        if success:
            (verification.output / 'summary.json').write_text(json.dumps(dict(result='passed', checks=len(verification.evidence), sourceSha256=source_hash), indent=2) + '\n')
            print('Container hardening verification passed.', flush=True)
    return 0 if success else 1


if __name__ == '__main__':
    def interrupted(signum, frame):
        raise KeyboardInterrupt('Interrupted')
    signal.signal(signal.SIGTERM, interrupted)
    sys.exit(main())
