#!/usr/bin/env python3
"""Failure experiments against the real, running stack.

Each experiment breaks something with `docker compose kill` (SIGKILL: no graceful shutdown), then
checks the promises the platform makes. Everything printed is observed, nothing is assumed. Any
broken promise fails the run. Standard library only.

    docker compose -f compose.yaml -f failure-testing/compose.failure.yaml up -d --build --wait
    python3 failure-testing/run.py
"""
import concurrent.futures
import json
import pathlib
import random
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from decimal import Decimal

API = "http://localhost:8080"
RECEIVER = "http://localhost:9099/received"
RECEIVER_INSIDE_COMPOSE = "http://receiver:9000/hooks"
COMPOSE = ["docker", "compose", "-f", "compose.yaml", "-f", "failure-testing/compose.failure.yaml"]
TIMEOUT = 120

results = []
timeline = []
started = time.monotonic()


def note(message):
    line = f"[{time.monotonic() - started:7.1f}s] {message}"
    timeline.append(line)
    print(line, flush=True)


def check(experiment, claim, passed, evidence=""):
    results.append({"experiment": experiment, "claim": claim, "passed": bool(passed), "evidence": str(evidence)})
    note(f"{'PASS' if passed else 'FAIL'}: {claim} {f'({evidence})' if evidence != '' else ''}")


def compose(*args):
    subprocess.run(COMPOSE + list(args), check=True, capture_output=True)


def kill(service):
    note(f"KILL {service} (SIGKILL)")
    compose("kill", "-s", "SIGKILL", service)


def start(service):
    note(f"START {service}")
    compose("start", service)


def sql(query):
    output = subprocess.run(COMPOSE + ["exec", "-T", "postgres", "psql", "-U", "ledgerforge", "-d", "ledgerforge",
                                       "-At", "-c", query], check=True, capture_output=True, text=True)
    return output.stdout.strip()


def call(method, path, body=None, token=None, key=None, timeout=10):
    """Returns (status, json). Status is None if the connection itself failed: the outcome is unknown."""
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if key:
        headers["Idempotency-Key"] = key
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(API + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        raw = e.read()
        return e.code, json.loads(raw) if raw else {}
    except OSError:
        return None, {}


def wait_for(condition, what, timeout=TIMEOUT):
    began = time.monotonic()
    while time.monotonic() - began < timeout:
        value = condition()
        if value:
            elapsed = time.monotonic() - began
            note(f"{what}: after {elapsed:.1f}s")
            return value, elapsed
        time.sleep(0.5)
    note(f"TIMED OUT waiting for: {what}")
    return None, timeout


def token(subject, roles, customer=None):
    body = {"subject": subject, "roles": roles}
    if customer:
        body["customerId"] = customer
    return call("POST", "/api/v1/auth/token", body)[1]["accessToken"]


def admin():
    return token("failure-tester", ["ADMIN"])


def wallet(funds=None):
    """A customer and wallet; returns (wallet id, owner's token)."""
    ops = admin()
    customer = call("POST", "/api/v1/customers", {"email": f"{uuid.uuid4()}@example.com", "fullName": "Failure Test"}, ops)[1]["id"]
    owner = token(customer, ["CUSTOMER"], customer)
    account = call("POST", "/api/v1/accounts", {"customerId": customer, "currency": "EUR"}, owner)[1]["id"]
    if funds:
        call("POST", f"/api/v1/accounts/{account}/deposits", {"amount": funds, "currency": "EUR"}, ops)
    return account, owner


def new_payment(payer, merchant, amount="30.00"):
    """Create and authorize, leaving capture, the step that publishes an event, to the experiment."""
    payment = call("POST", "/api/v1/payments", {"payerAccountId": payer[0], "merchantAccountId": merchant[0],
                                                "amount": amount, "currency": "EUR"}, payer[1])[1]["id"]
    call("POST", f"/api/v1/payments/{payment}/authorize", None, payer[1])
    return payment


def register_webhook(merchant):
    return call("POST", "/api/v1/webhook-endpoints", {"accountId": merchant[0], "url": RECEIVER_INSIDE_COMPOSE,
                                                      "eventTypes": ["payment.captured"]}, merchant[1])[1]["id"]


def received_for(payment):
    try:
        with urllib.request.urlopen(RECEIVER, timeout=3) as response:
            return [r for r in json.load(response) if r["event"]["data"].get("paymentId") == payment]
    except OSError:
        return []


def balance(account):
    return Decimal(call("GET", f"/api/v1/accounts/{account}", None, admin())[1]["balance"])


def healthy():
    return call("GET", "/actuator/health/readiness", timeout=3)[0] == 200


# ---- experiment 1 ---------------------------------------------------------------------------------

def kill_kafka():
    name = "Kill Kafka"
    note(f"--- {name}")
    payer, merchant = wallet("100.00"), wallet()
    register_webhook(merchant)
    payment = new_payment(payer, merchant)

    kill("kafka")
    status, body = call("POST", f"/api/v1/payments/{payment}/capture", None, merchant[1], timeout=30)
    check(name, "Payment succeeds with Kafka down", status == 200 and body.get("status") == "CAPTURED", f"HTTP {status}")
    check(name, "Money moved", balance(merchant[0]) == Decimal("30.00"), balance(merchant[0]))

    event = f"SELECT count(*) FROM outbox_events WHERE aggregate_id = '{payment}' AND published_at IS NULL"
    check(name, "Outbox retains the event", sql(event) == "1", "1 unpublished row")
    time.sleep(15)
    attempts = sql(f"SELECT attempts FROM outbox_events WHERE aggregate_id = '{payment}'")
    check(name, "Still retained after 15 s, with failed attempts recorded",
          sql(event) == "1" and int(attempts) >= 1, f"attempts={attempts}")

    start("kafka")
    _, published_after = wait_for(lambda: sql(event) == "0", "Event published after Kafka returned", 180)
    check(name, "Event published", sql(event) == "0", f"{published_after:.1f}s after restart")
    delivered, delivered_after = wait_for(lambda: received_for(payment), "Webhook received", 120)
    check(name, "The event went all the way through to the merchant, once", delivered and len(delivered) == 1,
          f"{delivered_after:.1f}s after publishing")


# ---- experiment 2 ---------------------------------------------------------------------------------

def kill_webhook_receiver():
    name = "Kill webhook receiver"
    note(f"--- {name}")
    payer, merchant = wallet("100.00"), wallet()
    endpoint = register_webhook(merchant)
    payment = new_payment(payer, merchant)

    kill("receiver")
    status, _ = call("POST", f"/api/v1/payments/{payment}/capture", None, merchant[1])
    check(name, "The payment does not depend on the merchant's server", status == 200, f"HTTP {status}")

    def delivery():
        items = call("GET", f"/api/v1/webhook-endpoints/{endpoint}/deliveries", None, merchant[1])[1]
        return items[0] if items else None

    first, _ = wait_for(delivery, "Delivery queued", 60)
    detail = lambda: call("GET", f"/api/v1/webhook-deliveries/{first['id']}", None, merchant[1])[1]
    wait_for(lambda: len(detail().get("attempts", [])) >= 2, "Two attempts failed, with backoff between them", 90)

    start("receiver")
    wait_for(lambda: detail().get("status") == "DELIVERED", "Delivered after the receiver returned", 150)
    final = detail()
    attempts = final.get("attempts", [])
    check(name, "Webhook fails, retries, then delivery succeeds", final.get("status") == "DELIVERED",
          f"{len(attempts)} attempts")
    check(name, "Every attempt is stored", attempts and attempts[-1]["outcome"] == "SUCCESS"
          and all(a["outcome"] == "FAILURE" for a in attempts[:-1]),
          [f"{a['attemptNumber']}:{a['outcome']}" for a in attempts])
    gaps = [(attempts[i + 1]["startedAt"], attempts[i]["startedAt"]) for i in range(len(attempts) - 1)]
    note(f"Attempt times: {[a['startedAt'] for a in attempts]}")
    check(name, "The merchant got the event exactly once", len(received_for(payment)) == 1, len(gaps) + 1)


# ---- experiments 3 and 4 --------------------------------------------------------------------------

def crash_during_transfers(service, name):
    """SIGKILL `service` under load. Every wallet must balance, and nothing acknowledged may vanish."""
    note(f"--- {name}")
    wallets = [wallet("1000.00") for _ in range(4)]
    ids = [w[0] for w in wallets]
    in_clause = ",".join(f"'{i}'" for i in ids)
    rng = random.Random(2026)
    stop = {"now": False}
    acknowledged, in_doubt, refused = [], [], []

    def client(worker):
        local = random.Random(worker)
        while not stop["now"]:
            source, destination = local.sample(wallets, 2)
            key = str(uuid.uuid4())
            body = {"sourceAccountId": source[0], "destinationAccountId": destination[0],
                    "amount": f"{local.randint(1, 5000) / 100:.2f}", "currency": "EUR"}
            status, response = call("POST", "/api/v1/transfers", body, source[1], key, timeout=5)
            if status == 201:
                acknowledged.append(response["id"])
            elif status == 422:
                refused.append(key)
            else:
                # No answer, or a 5xx: the client cannot know whether the money moved.
                in_doubt.append((key, body, source[1]))
                time.sleep(0.2)

    with concurrent.futures.ThreadPoolExecutor(8) as pool:
        workers = [pool.submit(client, i) for i in range(8)]
        time.sleep(4)
        before = len(acknowledged)
        note(f"{before} transfers acknowledged, killing {service} mid-load")
        kill(service)
        time.sleep(3)
        start(service)
        wait_for(healthy, f"{service} is back and the service is ready", 180)
        time.sleep(2)
        stop["now"] = True
        for worker in workers:
            worker.result()

    note(f"Clients saw {len(acknowledged)} acknowledged, {len(refused)} refused, {len(in_doubt)} in doubt")
    check(name, "Clients really were cut off mid-request", len(in_doubt) > 0, f"{len(in_doubt)} requests with no answer")

    # A careful client retries everything in doubt with the same key: each must resolve, never twice.
    resolved = {}
    for key, body, owner in in_doubt:
        status, response = call("POST", "/api/v1/transfers", body, owner, key, timeout=30)
        resolved[key] = (status, response.get("id"))
        if status == 201:
            acknowledged.append(response["id"])
    check(name, "Every in-doubt retry got a definite answer", all(s in (201, 422) for s, _ in resolved.values()),
          sorted({s for s, _ in resolved.values()}, key=str))

    ack_list = ",".join(f"'{t}'" for t in set(acknowledged))
    found = int(sql(f"SELECT count(*) FROM transfers WHERE status = 'COMPLETED' AND id IN ({ack_list})"))
    check(name, "Nothing acknowledged was lost", found == len(set(acknowledged)), f"{found} of {len(set(acknowledged))} present")

    total = sum(balance(i) for i in ids)
    check(name, "No missing money: the wallets still total EUR 4000.00", total == Decimal("4000.00"), total)

    pending = sql(f"SELECT count(*) FROM transfers WHERE status <> 'COMPLETED' AND source_account_id IN ({in_clause})")
    check(name, "No half-transfer: none left incomplete", pending == "0", f"{pending} incomplete")

    legs = sql(f"""SELECT count(*) FROM (
        SELECT e.transaction_id FROM ledger_entries e
        JOIN ledger_transactions t ON t.id = e.transaction_id AND t.type = 'TRANSFER'
        WHERE e.account_id IN ({in_clause})
        GROUP BY e.transaction_id HAVING count(*) <> 2) x""")
    check(name, "No half-transfer: every ledger transaction has both legs", legs == "0", f"{legs} one-sided")

    booked = int(sql(f"SELECT count(*) FROM transfers WHERE source_account_id IN ({in_clause})"))
    check(name, "Every stored transfer was acknowledged: retries created no duplicates",
          booked == len(set(acknowledged)), f"{booked} stored, {len(set(acknowledged))} acknowledged")

    report = call("GET", "/api/v1/admin/reconciliation", None, admin())[1]
    check(name, "The whole ledger reconciles", report.get("consistent") is True, report.get("consistent"))


# ---- experiment 5 ---------------------------------------------------------------------------------

def kill_redis():
    name = "Kill Redis"
    note(f"--- {name}")
    payer, merchant = wallet("100.00"), wallet()
    kill("redis")
    began = time.monotonic()
    payment = new_payment(payer, merchant)
    status, _ = call("POST", f"/api/v1/payments/{payment}/capture", None, merchant[1])
    check(name, "Payments keep working without Redis", status == 200, f"HTTP {status}, {time.monotonic() - began:.1f}s for the flow")
    check(name, "The balance is right", balance(merchant[0]) == Decimal("30.00"), balance(merchant[0]))
    degraded = sql(f"SELECT degraded FROM risk_decisions WHERE payment_id = '{payment}'")
    check(name, "The risk decision records that it ran without Redis", degraded == "t", f"degraded={degraded}")
    start("redis")
    wait_for(lambda: call("GET", "/actuator/health", timeout=3)[1].get("status") == "UP", "Health is UP again", 60)


def main():
    out = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "failure-testing/results")
    out.mkdir(parents=True, exist_ok=True)
    wait_for(healthy, "Stack ready")
    for experiment in (kill_kafka, kill_webhook_receiver,
                       lambda: crash_during_transfers("app", "Kill the application during transfers"),
                       lambda: crash_during_transfers("postgres", "Kill PostgreSQL during transfers"),
                       kill_redis):
        experiment()

    (out / "results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    (out / "timeline.txt").write_text("\n".join(timeline) + "\n", encoding="utf-8")
    failed = [r for r in results if not r["passed"]]
    print(f"\n{len(results) - len(failed)} of {len(results)} checks passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
