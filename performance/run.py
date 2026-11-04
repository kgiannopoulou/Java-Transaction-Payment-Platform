#!/usr/bin/env python3
"""Runs the load tests at each user level and records what actually happened.

Expects the stack to be running (see README.md). For each test and level it runs k6, samples
`docker stats` throughout, then asks Prometheus about the same window. Writes results.json and
results.md to the output directory. Standard library only.
"""
import argparse
import datetime
import json
import os
import pathlib
import platform
import re
import statistics
import subprocess
import threading
import time
import urllib.parse
import urllib.request

PROMETHEUS = "http://localhost:9091"
K6_IMAGE = "grafana/k6:1.3.0"
SERVICES = ("app", "postgres", "kafka", "redis")


def sh(*command):
    return subprocess.run(command, capture_output=True, text=True).stdout


def prometheus(query):
    url = f"{PROMETHEUS}/api/v1/query?query={urllib.parse.quote(query)}"
    with urllib.request.urlopen(url, timeout=10) as response:
        result = json.load(response)["data"]["result"]
    return float(result[0]["value"][1]) if result else None


def to_mib(text):
    number, unit = re.match(r"([\d.]+)\s*([KMG]?i?B)", text).groups()
    return float(number) * {"B": 1 / 1048576, "KiB": 1 / 1024, "MiB": 1, "GiB": 1024}[unit]


class DockerStats(threading.Thread):
    """CPU and memory per container, including the load generator, every few seconds."""

    def __init__(self, project):
        super().__init__(daemon=True)
        self.project = project
        self.samples = {}
        self.running = True

    def run(self):
        while self.running:
            lines = sh("docker", "stats", "--no-stream", "--format", "{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}").splitlines()
            for line in lines:
                name, cpu, memory = line.split("|")
                service = next((s for s in SERVICES if name.startswith(f"{self.project}-{s}-")), None)
                if name.startswith("k6-"):
                    service = "k6"
                if service:
                    self.samples.setdefault(service, []).append(
                        (time.monotonic(), float(cpu.rstrip("%")), to_mib(memory.split("/")[0].strip())))
            time.sleep(2)

    def summary(self, seconds):
        """Only the load phase: the last `seconds` before k6 ended, not its setup."""
        since = time.monotonic() - seconds
        recent = {s: [v for v in values if v[0] >= since] for s, values in self.samples.items()}
        return {service: {"cpu_avg_percent": round(statistics.mean(c for _, c, _ in values), 1),
                          "cpu_max_percent": round(max(c for _, c, _ in values), 1),
                          "memory_max_mib": round(max(m for _, _, m in values))}
                for service, values in recent.items() if values}


def run_level(test, users, duration, network, out):
    name = f"{test}-{users}"
    window = int(duration.rstrip("s")) + 5
    print(f"== {test}: {users} users for {duration}", flush=True)
    root = pathlib.Path(__file__).resolve().parent
    command = ["docker", "run", "--rm", "--name", f"k6-{name}", "--network", network,
               "-v", f"{root}:/scripts:ro", "-v", f"{out.resolve()}:/out",
               "-e", "API_URL=http://app:8080", "-e", f"USERS={users}", "-e", f"DURATION={duration}",
               K6_IMAGE, "run", "--quiet", "--summary-export", f"/out/{name}.json",
               f"/scripts/{test}-load-test.js"]
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    log = process.stdout.readlines()
    process.wait()
    (out / f"{name}.log").write_text("".join(log[-200:]), encoding="utf-8")
    return window, process.returncode


def measured(test, users, duration, network, out):
    stats = DockerStats(network.removesuffix("_default"))
    stats.start()
    window, code = run_level(test, users, duration, network, out)
    stats.running = False
    containers = stats.summary(window)
    time.sleep(6)  # one more Prometheus scrape

    summary = json.loads((out / f"{test}-{users}.json").read_text(encoding="utf-8"))
    metrics = summary["metrics"]
    seconds = int(duration.rstrip("s"))
    load = "{scenario:load}"
    duration_ms = metrics["http_req_duration" + load]
    failed = metrics["http_req_failed" + load]
    requests = metrics["http_reqs" + load]["count"]
    span = f"[{window}s]"
    result = {
        "test": test,
        "users": users,
        "k6_exit_code": code,
        "requests": requests,
        "requests_per_second": round(requests / seconds, 1),
        "latency_ms": {k: round(duration_ms[k], 1) for k in ("med", "p(95)", "p(99)", "max")},
        "error_rate_percent": round(100 * failed["value"], 3),
        "db_connections_active_max": prometheus(f"max_over_time(hikaricp_connections_active{span})"),
        "db_connections_pending_max": prometheus(f"max_over_time(hikaricp_connections_pending{span})"),
        "app_cpu_avg_percent_of_host": round(100 * (prometheus(f"avg_over_time(process_cpu_usage{span})") or 0), 1),
        "host_cpu_avg_percent": round(100 * (prometheus(f"avg_over_time(system_cpu_usage{span})") or 0), 1),
        "jvm_heap_max_mib": round((prometheus(f'max_over_time(sum(jvm_memory_used_bytes{{area="heap"}})[{window}s:5s])') or 0) / 1048576),
        "jvm_live_threads_max": prometheus(f"max_over_time(jvm_threads_live_threads{span})"),
        "outbox_pending_max": prometheus(f"max_over_time(outbox_pending_events{span})"),
        "containers": containers,
    }
    if "payment_flow_duration" in metrics:
        flow = metrics["payment_flow_duration"]
        result["flow_latency_ms"] = {k: round(flow[k], 1) for k in ("med", "p(95)", "p(99)")}
    result["outbox_drain_seconds"] = drain_outbox()
    print(json.dumps(result), flush=True)
    return result


def drain_outbox(limit=600):
    """Waits until the relay has caught up, so one level's backlog cannot affect the next."""
    began = time.monotonic()
    while (prometheus("outbox_pending_events") or 0) > 0 and time.monotonic() - began < limit:
        time.sleep(5)
    return round(time.monotonic() - began)


def environment():
    def first(command, pattern):
        match = re.search(pattern, sh(*command))
        return match.group(1).strip() if match else "unknown"

    return {
        "date": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d %H:%M UTC"),
        "commit": sh("git", "rev-parse", "--short", "HEAD").strip(),
        "runner": os.environ.get("RUNNER_NAME", platform.node()),
        "os": platform.platform(),
        "cpu_model": first(["lscpu"], r"Model name:\s*(.+)"),
        "cpus": os.cpu_count(),
        "memory": first(["free", "-h"], r"Mem:\s+(\S+)"),
        "k6": K6_IMAGE,
    }


def warm_up(network, out):
    """JIT compilation and connection pools, so the first level does not measure a cold JVM."""
    print("== warm-up (not measured)", flush=True)
    run_level("transfer", 20, "30s", network, out)
    run_level("payment", 20, "30s", network, out)
    drain_outbox()


def markdown(env, results, duration):
    lines = [f"Measured {env['date']} at commit `{env['commit']}`. {env['cpus']} vCPUs ({env['cpu_model']}), "
             f"{env['memory']} RAM, {env['os']}. Application, PostgreSQL, Kafka, Redis, Prometheus and k6 share this "
             f"one machine. {duration} per level.\n"]
    for test in sorted({r["test"] for r in results}, reverse=True):
        lines += [f"### {test}\n",
                  "| Users | Requests/s | p50 | p95 | p99 | Errors | DB connections (max active / max waiting) | "
                  "App CPU | PostgreSQL CPU | Heap max | Outbox backlog | Backlog drained in |",
                  "|---|---|---|---|---|---|---|---|---|---|---|---|"]
        for r in (r for r in results if r["test"] == test):
            latency = r["latency_ms"]
            app = r["containers"].get("app", {})
            database = r["containers"].get("postgres", {})
            lines.append(
                f"| {r['users']} | {r['requests_per_second']} | {latency['med']} ms | {latency['p(95)']} ms | "
                f"{latency['p(99)']} ms | {r['error_rate_percent']}% | "
                f"{r['db_connections_active_max']:.0f} / {r['db_connections_pending_max']:.0f} | "
                f"{app.get('cpu_avg_percent', '?')}% | {database.get('cpu_avg_percent', '?')}% | {r['jvm_heap_max_mib']} MiB | "
                f"{r['outbox_pending_max']:.0f} | {r['outbox_drain_seconds']} s |")
        lines.append("")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--users", default="50,100,500,1000")
    parser.add_argument("--tests", default="transfer,payment")
    parser.add_argument("--duration", default="60s")
    parser.add_argument("--network", default="ledgerforge_default")
    parser.add_argument("--out", default="performance/results/latest")
    args = parser.parse_args()

    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    os.chmod(out, 0o777)  # k6 runs as a non-root user inside its container
    env = environment()
    results = []
    warm_up(args.network, out)
    for test in args.tests.split(","):
        for users in (int(u) for u in args.users.split(",")):
            results.append(measured(test, users, args.duration, args.network, out))
            (out / "results.json").write_text(json.dumps({"environment": env, "results": results}, indent=2), encoding="utf-8")
            time.sleep(10)
    (out / "results.md").write_text(markdown(env, results, args.duration), encoding="utf-8")
    print(markdown(env, results, args.duration))


if __name__ == "__main__":
    main()
