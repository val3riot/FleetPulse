#!/usr/bin/env python3
import argparse


from pathlib import Path
from markdown_it import MarkdownIt
from jsonschema import Draft202012Validator, FormatChecker
import json, re

parser = argparse.ArgumentParser()
parser.add_argument("--output", required=True)
args = parser.parse_args()

out = Path(args.output)
spec = json.loads((out / "openapi.json").read_text())
doc = Path("docs/09_SPECIFICA_API.md").read_text()
examples = [
    json.loads(t.content)
    for t in MarkdownIt().parse(doc)
    if t.type == "fence" and t.info == "json"
]
names = [
    "ApiErrorResponse",
    "PagedResponseVehicleResponse",
    "PagedResponseVehicleResponse",
    "CreateVehicleRequest",
    "VehicleResponse",
    "ChangeVehicleStatusRequest",
    "VehicleStateResponse",
    "DashboardResponse",
    "TelemetryHistoryResponse",
    "MaintenanceAlertResponse",
    "ChangeAlertStatusRequest",
]
assert len(examples) == len(names)
for example, name in zip(examples, names):
    schema = {"$ref": "#/components/schemas/" + name, "components": spec["components"]}
    Draft202012Validator(schema, format_checker=FormatChecker()).validate(example)

    def exact_fields(value, schema):
        if "$ref" in schema:
            schema = spec["components"]["schemas"][schema["$ref"].rsplit("/", 1)[-1]]
        if isinstance(value, dict) and "properties" in schema:
            assert not set(value) - set(schema["properties"]), str(
                set(value) - set(schema["properties"])
            )
            for k, v in value.items():
                exact_fields(v, schema["properties"][k])
        if isinstance(value, list) and "items" in schema:
            for v in value:
                exact_fields(v, schema["items"])

    exact_fields(example, spec["components"]["schemas"][name])
ops = {(p, m.upper()) for p, methods in spec["paths"].items() for m in methods}
listed = {
    (p, m)
    for m, p in re.findall(r"^###[^\n]*`(GET|POST|PATCH) (/api/v1/[^`]+)`", doc, re.M)
}
assert ops == listed, (ops - listed, listed - ops)
for p, methods in spec["paths"].items():
    for m, o in methods.items():
        for code, r in o["responses"].items():
            if int(code) >= 400:
                assert r["content"]["application/json"]["schema"]["$ref"].endswith(
                    "/ApiErrorResponse"
                )
# The catalog must contain exactly the public error enum, with the same HTTP statuses.
java = Path(
    "services/fleet-api/src/main/java/it/fleetpulse/api/common/ErrorCode.java"
).read_text()
statuses = {
    "BAD_REQUEST": 400,
    "NOT_FOUND": 404,
    "CONFLICT": 409,
    "SERVICE_UNAVAILABLE": 503,
    "METHOD_NOT_ALLOWED": 405,
    "UNSUPPORTED_MEDIA_TYPE": 415,
    "INTERNAL_SERVER_ERROR": 500,
}
actual = {
    k: statuses[v] for k, v in re.findall(r"([A-Z_]+)\(\s*HttpStatus\.([A-Z_]+)", java)
}
catalog = Path("docs/15_CODICI_ERRORE_REST.md").read_text()
documented = {
    k: int(v) for k, v in re.findall(r"^\| `([A-Z_]+)` \| `(\d+) ", catalog, re.M)
}
assert actual == documented, (actual, documented)
# Verify column names/types/nullability and every explicit constraint/index in the schema excerpts.
data = Path("docs/07_DATA_MODEL.md").read_text()
cols = json.loads((out / "columns.json").read_text())
indexes = json.loads((out / "indexes.json").read_text())
constraints = json.loads((out / "constraints.json").read_text())
aliases = {"timestamptz": "timestamp with time zone", "varchar": "character varying"}
column_count = 0
for table, body in re.findall(r"create table (\w+) \((.*?)\n\);", data, re.S | re.I):
    expected = {}
    for name, typ, rest in re.findall(
        r"^    (\w+) (uuid|bigint|integer|timestamptz|double precision|varchar(?:\(\d+\))?)([^\n]*)",
        body,
        re.M,
    ):
        typ = aliases.get(re.sub(r"\(\d+\)", "", typ), typ)
        expected[name] = (
            typ,
            "NO" if "not null" in rest or "identity" in rest else "YES",
        )
    actualcols = {
        c["column_name"]: (c["data_type"], c["is_nullable"])
        for c in cols
        if c["table_name"] == table
    }
    assert expected == actualcols, (table, expected, actualcols)
    column_count += len(expected)
for name in re.findall(r"^    constraint (\w+)", data, re.M | re.I):
    assert name in {c["conname"] for c in constraints}, name
for name, table, fields in re.findall(
    r"create index (\w+)\s+on (\w+) \((.*?)\);", data, re.S | re.I
):
    idx = next(i for i in indexes if i["indexname"] == name)
    assert idx["tablename"] == table
    assert re.sub(r"\s+", "", fields.lower()) in re.sub(
        r"\s+", "", idx["indexdef"].lower()
    ), name
for name in re.findall(r"`(ix_\w+)`", data):
    assert name in {i["indexname"] for i in indexes}, name
migrations = json.loads((out / "migrations.json").read_text())
assert [m["version"] for m in migrations] == ["1", "2", "3", "4", "5"] and all(
    m["success"] for m in migrations
)
# Check every operation's documented success/error statuses and query defaults.
expected_responses = {
    ("/api/v1/vehicles", "get"): {200, 400, 500, 503},
    ("/api/v1/vehicles", "post"): {201, 400, 409, 415, 500, 503},
    ("/api/v1/vehicles/{vehicleId}", "get"): {200, 400, 404, 500, 503},
    ("/api/v1/vehicles/{vehicleId}/status", "patch"): {200, 400, 404, 415, 500, 503},
    ("/api/v1/vehicles/{vehicleId}/state", "get"): {200, 400, 404, 500, 503},
    ("/api/v1/dashboard", "get"): {200, 500, 503},
    ("/api/v1/vehicles/{vehicleId}/telemetry", "get"): {200, 400, 404, 500, 503},
    ("/api/v1/vehicles/{vehicleId}/alerts", "get"): {200, 400, 404, 500, 503},
    ("/api/v1/alerts", "get"): {200, 400, 500, 503},
    ("/api/v1/alerts/{alertId}", "get"): {200, 400, 404, 500, 503},
    ("/api/v1/alerts/{alertId}", "patch"): {200, 400, 404, 409, 415, 500, 503},
}
for (path, method), expected in expected_responses.items():
    assert {int(c) for c in spec["paths"][path][method]["responses"]} == expected, (
        path,
        method,
    )
for path, sort, size in [
    ("/api/v1/vehicles", "createdAt,desc", 20),
    ("/api/v1/vehicles/{vehicleId}/telemetry", "observedAt,desc", 50),
    ("/api/v1/alerts", "createdAt,desc", 50),
    ("/api/v1/vehicles/{vehicleId}/alerts", "createdAt,desc", 50),
]:
    parameters = {p["name"]: p for p in spec["paths"][path]["get"]["parameters"]}
    for name, default in [("page", 0), ("size", size), ("sort", sort)]:
        assert (
            not parameters[name]["required"]
            and parameters[name]["schema"]["default"] == default
        ), (path, name)
    assert parameters["size"]["schema"]["maximum"] == 100
for p in spec["paths"]["/api/v1/vehicles/{vehicleId}/telemetry"]["get"]["parameters"]:
    if p["name"] in ("from", "to"):
        assert p["required"] and p["schema"]["format"] == "date-time"
result = dict(
    result="passed",
    jsonExamples=len(examples),
    operations=len(ops),
    errorCodes=len(documented),
    tables=3,
    columns=column_count,
    migrations=5,
)
(out / "contract-audit.json").write_text(json.dumps(result, indent=2))
print(result)
