#!/usr/bin/env python3
import argparse
from pathlib import Path
import json, re, subprocess

example = dict(
    line.split("=", 1)
    for line in Path(".env.example").read_text().splitlines()
    if line and not line.startswith("#")
)
config = json.loads(
    subprocess.check_output(
        [
            "docker",
            "compose",
            "--env-file",
            ".env.example",
            "config",
            "--format",
            "json",
        ],
        text=True,
    )
)
ports = {
    "postgres": ("POSTGRES_PORT", 5432),
    "redis": ("REDIS_PORT", 6379),
    "kafka": ("KAFKA_PORT", 9092),
    "fleet-api": ("FLEET_API_PORT", 8080),
    "telemetry-processor": ("PROCESSOR_HTTP_PORT", 8080),
    "prometheus": ("PROMETHEUS_PORT", 9090),
    "grafana": ("GRAFANA_PORT", 3000),
}
for service, (variable, internal) in ports.items():
    mappings = config["services"][service]["ports"]
    assert len(mappings) == 1
    p = mappings[0]
    assert (
        p["host_ip"] == "127.0.0.1"
        and p["published"] == example[variable]
        and p["target"] == internal
    )
for variable, internal in [("GATEWAY_HTTP_PORT", 8080), ("GATEWAY_TCP_PORT", 7000)]:
    assert any(
        p["published"] == example[variable]
        and p["target"] == internal
        and p["host_ip"] == "127.0.0.1"
        for p in config["services"]["telemetry-gateway"]["ports"]
    )
doc = Path("docs/13_DEPLOYMENT.md").read_text()
for block in re.findall(r"```dotenv\n(.*?)```", doc, re.S):
    for line in block.splitlines():
        if "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1)
            assert example[k] == v, (k, v)
topics = [
    example[k]
    for k in ["KAFKA_TOPIC_RAW", "KAFKA_TOPIC_REJECTED", "KAFKA_TOPIC_DEAD_LETTER"]
]
for topic in topics:
    assert topic in config["services"]["kafka-init"]["command"][0]
    assert topic in Path("docs/08_EVENT_MODEL.md").read_text() and topic in doc
assert "kafka:19092" in doc and "localhost:9092" in doc
assert (
    config["services"]["kafka"]["environment"]["KAFKA_ADVERTISED_LISTENERS"]
    == "INTERNAL://kafka:19092,EXTERNAL://localhost:" + example["KAFKA_PORT"]
)
# FP-056 adds producer limits to the configuration contract checked by FP-055.
gateway = config["services"]["telemetry-gateway"]["environment"]
yaml = Path(
    "services/telemetry-gateway/src/main/resources/application.yaml"
).read_text()
for key in (
    "KAFKA_CONFIRMATION_TIMEOUT",
    "GATEWAY_KAFKA_MAX_BLOCK_MS",
    "GATEWAY_KAFKA_REQUEST_TIMEOUT_MS",
    "GATEWAY_KAFKA_DELIVERY_TIMEOUT_MS",
    "GATEWAY_KAFKA_LINGER_MS",
):
    assert str(gateway[key]) == example[key], key
    assert "${" + key + ":" + example[key] + "}" in yaml, key
block = int(example["GATEWAY_KAFKA_MAX_BLOCK_MS"])
request = int(example["GATEWAY_KAFKA_REQUEST_TIMEOUT_MS"])
delivery = int(example["GATEWAY_KAFKA_DELIVERY_TIMEOUT_MS"])
linger = int(example["GATEWAY_KAFKA_LINGER_MS"])
assert request + linger <= delivery
assert block + delivery <= 5000 and example["KAFKA_CONFIRMATION_TIMEOUT"] == "5s"
result = dict(
    result="passed", hostPortMappings=9, topics=topics, envSnippetMatchesExample=True
)
print(result)
