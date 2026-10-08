#!/usr/bin/env python3
"""Verify the running Compose Prometheus stack using only Python's stdlib."""

import argparse
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import subprocess
import sys
import time
from urllib.error import URLError
from urllib.parse import urlencode
from urllib.request import urlopen


ROOT = Path(__file__).resolve().parents[2]
METRICS = {
    "fleet-api": "fleetpulse_api_cache_hits_total",
    "telemetry-gateway": "fleetpulse_gateway_connections_active",
    "telemetry-processor": "fleetpulse_processor_events_total",
}


class Verification:
    def __init__(self, url, timeout):
        self.url = url.rstrip("/")
        self.timeout = timeout
        self.checks = []

    def api(self, path, **params):
        query = "?" + urlencode(params) if params else ""
        with urlopen(self.url + "/api/v1/" + path + query, timeout=5) as response:
            body = json.load(response)
        if body.get("status") != "success" or body.get("warnings"):
            raise ValueError("Prometheus API failed or returned warnings")
        return body["data"]

    def query(self, expression):
        data = self.api("query", query=expression)
        if data["resultType"] != "vector":
            raise ValueError("Expected an instant vector")
        return data["result"]

    def wait(self, name, probe):
        start = time.monotonic()
        deadline = start + self.timeout
        last_error = "condition not met"
        while True:
            try:
                evidence = probe()
                if evidence:
                    self.checks.append({"check": name, "seconds": round(time.monotonic() - start, 3),
                                        "evidence": evidence})
                    print("PASS " + name, flush=True)
                    return
            except (URLError, TimeoutError, ValueError, KeyError) as error:
                last_error = type(error).__name__ + ": " + str(error)
            if time.monotonic() >= deadline:
                raise RuntimeError(name + ": " + last_error)
            time.sleep(min(2, max(0, deadline - time.monotonic())))

    def target(self, job, state):
        targets = [target for target in self.api("targets", state="active")["activeTargets"]
                   if target["labels"].get("job") == job]
        if len(targets) != 1:
            return None
        target = targets[0]
        expected = "up" if state else "down"
        if (target["health"] != expected or target["scrapeInterval"] != "15s"
                or target["scrapeTimeout"] != "5s"
                or target["scrapeUrl"] != f"http://{job}:8080/actuator/prometheus"
                or bool(target["lastError"]) == bool(state)):
            return None
        series = self.query(f'up{{job="{job}"}}')
        if len(series) != 1 or float(series[0]["value"][1]) != state:
            return None
        return {"job": job, "health": expected, "lastScrape": target["lastScrape"],
                "scrapeInterval": target["scrapeInterval"], "scrapeTimeout": target["scrapeTimeout"],
                "hasScrapeError": bool(target["lastError"]),
                "up": series[0]["value"]}

    def metric(self, job, name):
        series = self.query(f'{name}{{job="{job}"}}')
        if not series or not all(math.isfinite(float(item["value"][1])) for item in series):
            return None
        return {"query": f'{name}{{job="{job}"}}', "series": series}


def compose(*args):
    subprocess.run(["docker", "compose", *args], cwd=ROOT, check=True, timeout=60)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://localhost:9090")
    parser.add_argument("--timeout", type=float, default=90,
                        help="Maximum seconds per check (default: 90)")
    parser.add_argument("--exercise-recovery", action="store_true",
                        help="Stop/start each application sequentially; restore in finally")
    parser.add_argument("--report", type=Path, help="Write JSON evidence, also on failure")
    args = parser.parse_args()
    if not math.isfinite(args.timeout) or args.timeout <= 0:
        parser.error("--timeout must be finite and positive")
    verification = Verification(args.url, args.timeout)
    report = {"startedAt": datetime.now(timezone.utc).isoformat(), "success": False,
              "recoveryExercised": args.exercise_recovery, "checks": verification.checks}
    try:
        for job, custom in METRICS.items():
            verification.wait(job + " UP", lambda: verification.target(job, 1))
            for metric in ("jvm_memory_used_bytes", "jvm_threads_live_threads", custom):
                verification.wait(job + " " + metric, lambda: verification.metric(job, metric))
        if args.exercise_recovery:
            # Baseline UP above ensures we never start an intentionally stopped service.
            for job in METRICS:
                try:
                    compose("stop", "--timeout", "10", job)
                    verification.wait(job + " DOWN", lambda: verification.target(job, 0))
                finally:
                    compose("start", job)
                verification.wait(job + " recovered", lambda: verification.target(job, 1))
                verification.wait(job + " JVM recovered",
                                  lambda: verification.metric(job, "jvm_memory_used_bytes"))
                verification.wait(job + " custom recovered",
                                  lambda: verification.metric(job, METRICS[job]))
            for job in METRICS:
                verification.wait(job + " final UP", lambda: verification.target(job, 1))
        report["success"] = True
    except (RuntimeError, URLError, ValueError, subprocess.SubprocessError, KeyboardInterrupt) as error:
        report["error"] = type(error).__name__ + ": " + str(error)
        print("FAIL " + report["error"], file=sys.stderr)
    finally:
        report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return 0 if report["success"] else 1


if __name__ == "__main__":
    sys.exit(main())
