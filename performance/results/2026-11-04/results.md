Measured 2026-11-04 10:28 UTC at commit `a249f26`. 4 vCPUs (AMD EPYC 7763 64-Core Processor), 15Gi RAM, Linux-7.0.0-1019-azure-x86_64-with-glibc2.43. Application, PostgreSQL, Kafka, Redis, Prometheus and k6 share this one machine. 60s per level.

### transfer

| Users | Requests/s | p50 | p95 | p99 | Errors | DB connections (max active / max waiting) | App CPU | PostgreSQL CPU | Heap max | Outbox backlog | Backlog drained in |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 50 | 49.8 | 7.6 ms | 20.0 ms | 146.7 ms | 0% | 3 / 0 | 58.0% | 21.4% | 150 MiB | 35 | 0 s |
| 100 | 99.0 | 7.4 ms | 20.6 ms | 182.0 ms | 0% | 6 / 0 | 65.2% | 41.6% | 147 MiB | 63 | 0 s |
| 500 | 397.0 | 121.4 ms | 920.6 ms | 1590.9 ms | 0% | 20 / 188 | 118.6% | 163.0% | 289 MiB | 18772 | 110 s |
| 1000 | 438.6 | 989.9 ms | 3431.9 ms | 4924.8 ms | 0% | 20 / 674 | 108.3% | 181.6% | 538 MiB | 23582 | 135 s |

### payment

| Users | Requests/s | p50 | p95 | p99 | Errors | DB connections (max active / max waiting) | App CPU | PostgreSQL CPU | Heap max | Outbox backlog | Backlog drained in |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 50 | 145.6 | 6.4 ms | 26.1 ms | 167.9 ms | 0% | 4 / 0 | 61.0% | 49.9% | 523 MiB | 30 | 0 s |
| 100 | 265.1 | 11.7 ms | 210.1 ms | 330.0 ms | 0% | 20 / 27 | 91.6% | 77.7% | 516 MiB | 63 | 0 s |
| 500 | 350.6 | 1051.4 ms | 2139.0 ms | 2821.3 ms | 0% | 20 / 412 | 98.4% | 103.2% | 573 MiB | 189 | 0 s |
| 1000 | 352.5 | 2534.0 ms | 4701.7 ms | 5891.6 ms | 0% | 20 / 899 | 101.7% | 107.8% | 623 MiB | 814 | 0 s |
