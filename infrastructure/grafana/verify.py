#!/usr/bin/env python3
"""Check provisioned FleetPulse dashboard and execute every panel via Grafana."""

import argparse
import base64
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import sys
import time
from urllib.parse import urlencode
from urllib.request import Request, urlopen


DASHBOARD = Path(__file__).resolve().parent / "dashboards/fleetpulse-overview.json"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://localhost:3000")
    parser.add_argument("--report", type=Path)
    parser.add_argument("--require-samples", action="store_true",
                        help="Require finite samples for every query, including p95 and errors")
    args = parser.parse_args()
    user = os.environ.get("GRAFANA_ADMIN_USER")
    password = os.environ.get("GRAFANA_ADMIN_PASSWORD")
    if not user or not password:
        parser.error("Set GRAFANA_ADMIN_USER and GRAFANA_ADMIN_PASSWORD in the environment")
    auth = base64.b64encode((user + ":" + password).encode()).decode()
    report = {"startedAt": datetime.now(timezone.utc).isoformat(), "success": False, "checks": []}

    def api(path, body=None):
        request = Request(args.url.rstrip("/") + path,
                          data=json.dumps(body).encode() if body is not None else None,
                          headers={"Authorization": "Basic " + auth, "Content-Type": "application/json"})
        with urlopen(request, timeout=15) as response:
            return json.load(response)

    def check(name, condition, evidence=None):
        if not condition:
            raise ValueError("Check failed: " + name)
        report["checks"].append({"check": name, "evidence": evidence})
        print("PASS " + name, flush=True)

    try:
        source = json.loads(DASHBOARD.read_text())
        health = api("/api/health")
        check("Grafana database", health.get("database") == "ok", {"version": health.get("version")})
        datasources = api("/api/datasources")
        matches = [ds for ds in datasources if ds.get("name") == "Prometheus"]
        check("unique Prometheus datasource", len(matches) == 1)
        ds = matches[0]
        check("datasource contract", ds["uid"] == "fleetpulse-prometheus"
              and ds["type"] == "prometheus" and ds["url"] == "http://prometheus:9090"
              and ds["access"] == "proxy" and ds.get("readOnly") is True
              and ds.get("jsonData", {}).get("timeInterval") == "15s")
        found = api("/api/search?" + urlencode({"query": source["title"], "type": "dash-db"}))
        check("unique dashboard", len(found) == 1 and found[0]["uid"] == source["uid"])
        loaded = api("/api/dashboards/uid/" + source["uid"])
        check("provisioned dashboard", loaded["meta"].get("provisioned") is True
              and loaded["meta"].get("folderUid") == "fleetpulse"
              and loaded["dashboard"].get("editable") is False)
        # Compare the behavior-bearing fields, allowing Grafana to assign database id/version.
        for field in ("title", "uid", "panels", "refresh", "time", "templating"):
            check("dashboard " + field, loaded["dashboard"][field] == source[field])
        now = int(time.time() * 1000)
        for panel in source["panels"]:
            if panel["type"] == "row":
                continue
            for target in panel["targets"]:
                query = dict(target)
                # 30m at 15s steps, scrape interval 15s: minimum rate window is 1m.
                query["expr"] = query["expr"].replace("$__rate_interval", "1m")
                query.update({"intervalMs": 15000, "maxDataPoints": 1200})
                data = api("/api/ds/query", {"from": str(now - 1800000), "to": str(now),
                                            "queries": [query]})
                result = data["results"][target["refId"]]
                check(panel["title"] + " / " + target["refId"],
                      not result.get("error") and result.get("status", 200) == 200,
                      {"expr": query["expr"], "frames": len(result.get("frames", []))})
                samples = []
                for frame in result.get("frames", []):
                    for index, field in enumerate(frame["schema"]["fields"]):
                        if field["type"] == "number":
                            samples.extend(value for value in frame["data"]["values"][index]
                                           if isinstance(value, (int, float)) and math.isfinite(value))
                report["checks"][-1]["evidence"]["finiteSamples"] = len(samples)
                if args.require_samples:
                    check(panel["title"] + " / " + target["refId"] + " samples", bool(samples))
        report["success"] = True
    except (OSError, ValueError, KeyError, TypeError) as error:
        # Do not serialize response bodies or credentials into the evidence.
        report["error"] = type(error).__name__ + ": " + str(error)
        print("FAIL " + report["error"], file=sys.stderr)
    finally:
        report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps(report, indent=2) + "\n")
    return 0 if report["success"] else 1


if __name__ == "__main__":
    sys.exit(main())
