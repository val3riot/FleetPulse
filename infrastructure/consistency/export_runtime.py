#!/usr/bin/env python3
import argparse


from pathlib import Path
import sys, json, uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "e2e"))
from verify_nominal import Stack, command, require
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument("--output", required=True)
args = parser.parse_args()

out = Path(args.output).resolve()
out.mkdir(exist_ok=False)
s = Stack(out)
s.project = "fp055-" + uuid.uuid4().hex[:10]
s.base[3] = s.project
try:
    print("Starting " + s.project, flush=True)
    s.prepare()
    with urllib.request.urlopen(s.urls["fleet-api"] + "/v3/api-docs", timeout=10) as r:
        spec = json.load(r)
    (out / "openapi.json").write_text(json.dumps(spec, indent=2))
    queries = {
        "columns": "SELECT json_agg(row_to_json(t)) FROM (SELECT table_name,column_name,data_type,is_nullable,column_default FROM information_schema.columns WHERE table_schema='public' AND table_name IN ('vehicles','telemetry_samples','maintenance_alerts') ORDER BY table_name,ordinal_position)t",
        "constraints": "SELECT json_agg(row_to_json(t)) FROM (SELECT conrelid::regclass::text AS table_name,conname,pg_get_constraintdef(oid) AS definition FROM pg_constraint WHERE connamespace='public'::regnamespace ORDER BY conname)t",
        "indexes": "SELECT json_agg(row_to_json(t)) FROM (SELECT tablename,indexname,indexdef FROM pg_indexes WHERE schemaname='public' ORDER BY indexname)t",
        "migrations": "SELECT json_agg(row_to_json(t)) FROM (SELECT version,script,success FROM flyway_schema_history ORDER BY installed_rank)t",
    }
    for name, q in queries.items():
        (out / (name + ".json")).write_text(json.dumps(json.loads(s.sql(q)), indent=2))
    (out / "summary.json").write_text(
        json.dumps(
            dict(result="passed", project=s.project, paths=len(spec["paths"])), indent=2
        )
    )
finally:
    s.close()
    remaining = {
        kind: command(
            [
                "docker",
                kind,
                "ls",
                *(["-a"] if kind == "container" else []),
                "-q",
                "--filter",
                "label=com.docker.compose.project=" + s.project,
            ]
        ).splitlines()
        for kind in ("container", "network", "volume")
    }
    (out / "cleanup.json").write_text(json.dumps(remaining, indent=2))
    require(not any(remaining.values()), "Resources remain")
print("Runtime contracts exported; cleanup complete", flush=True)
