"""Container configuration and scan policies, independent of the Docker daemon."""
import re

PIN = re.compile(r'^.+:[^:@\s]+@sha256:[0-9a-f]{64}$')
APPS = {'fleet-api', 'telemetry-gateway', 'telemetry-processor', 'vehicle-simulator', 'fleet-dashboard'}
# Vendor-maintained images; application images are built from these bases only.
RUNTIME_REPOSITORIES = {
    'postgres': 'postgres', 'redis': 'redis', 'kafka': 'apache/kafka',
    'kafka-init': 'apache/kafka', 'flyway': 'flyway/flyway',
    'prometheus': 'prom/prometheus', 'grafana': 'grafana/grafana',
}
BUILD_REPOSITORIES = {
    name: {'MAVEN_IMAGE': 'maven', 'JAVA_RUNTIME_IMAGE': 'eclipse-temurin'}
    for name in APPS - {'fleet-dashboard'}
}
BUILD_REPOSITORIES['fleet-dashboard'] = {
    'FRONTEND_NODE_IMAGE': 'node', 'FRONTEND_NGINX_IMAGE': 'nginx/nginx-unprivileged',
}

MAX_MIB = {'fleet-api': 180, 'telemetry-gateway': 160, 'telemetry-processor': 200,
           'vehicle-simulator': 120, 'fleet-dashboard': 40, 'postgres': 160,
           'redis': 60, 'kafka': 350, 'kafka-init': 350, 'flyway': 600,
           'prometheus': 250, 'grafana': 400}


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def validate_config(config, dockerfiles):
    require(set(config['services']) == set(MAX_MIB), 'Unexpected service inventory')
    for name, service in config['services'].items():
        if 'build' in service:
            require(name in BUILD_REPOSITORIES, 'Vendor runtime must use its official image: ' + name)
            arguments = dict(re.findall(r'^ARG (\w+IMAGE)=(.+)$', dockerfiles[name], re.M))
            arguments.update(service['build'].get('args', {}))
            require(set(arguments) == set(BUILD_REPOSITORIES[name]) and all(PIN.fullmatch(value) for value in arguments.values()), 'Unpinned build image: ' + name)
            require(all(value.split(':', 1)[0] == BUILD_REPOSITORIES[name][key] for key, value in arguments.items()), 'Unofficial build base: ' + name)
        else:
            require(name in RUNTIME_REPOSITORIES, 'Application must use its reviewed Dockerfile: ' + name)
            require(PIN.fullmatch(service.get('image', '')), 'Unpinned runtime image: ' + name)
            require(service['image'].split(':', 1)[0] == RUNTIME_REPOSITORIES[name], 'Unofficial runtime image: ' + name)
        require(0 < int(service.get('mem_limit', 0)) <= 2 * 1024**3, 'Missing/invalid memory budget: ' + name)
        require(0 < float(service.get('cpus', 0)) <= 2, 'Missing/invalid CPU budget: ' + name)
        require(not service.get('privileged') and service.get('network_mode') != 'host', 'Unsafe runtime: ' + name)
        require(all(port.get('host_ip') == '127.0.0.1' for port in service.get('ports', [])), 'Public host binding: ' + name)
        require(all(mount.get('type') != 'bind' or mount.get('read_only') for mount in service.get('volumes', [])), 'Writable host mount: ' + name)
    return True


def validate_runtime_budget(name, service, host):
    # Compose serializes byte counts as decimal strings; the daemon returns integers.
    require(host['Memory'] == int(service['mem_limit']), 'Runtime memory budget mismatch: ' + name)
    require(host['NanoCpus'] == round(float(service['cpus']) * 1e9), 'Runtime CPU budget mismatch: ' + name)
    require(not host['Privileged'], 'Privileged container: ' + name)


def scan_result(report, image_id, osless_go_image=False):
    require(report.get('SchemaVersion') == 2 and report.get('ArtifactType') == 'container_image', 'Invalid image scan report')
    require(report.get('Metadata', {}).get('ImageID') == image_id, 'Scan image identity mismatch')
    require(report.get('CreatedAt') and report.get('Results'), 'Incomplete scan report')
    has_os = any(result.get('Class') == 'os-pkgs' for result in report['Results'])
    go_only = (osless_go_image and not report['Metadata'].get('OS')
               and all(result.get('Class') == 'lang-pkgs' and result.get('Type') == 'gobinary' for result in report['Results']))
    require(has_os or go_only, 'OS package scan missing')
    findings = [finding for result in report['Results'] for finding in result.get('Vulnerabilities', [])]
    require(all(item.get('Severity') in ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNKNOWN') for item in findings), 'Invalid finding severity')
    counts = {severity: sum(item.get('Severity') == severity for item in findings)
              for severity in ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNKNOWN')}
    return dict(counts=counts, passes=not counts['CRITICAL'] and not counts['HIGH'])
