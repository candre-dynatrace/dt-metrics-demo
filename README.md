# dt-metrics-demo

A Scala/ZIO demo application that continuously generates synthetic CPU, memory, and latency metrics and
ships them to Dynatrace via four independent ingestion channels simultaneously. Its purpose is to
demonstrate how Dynatrace can receive observability data from a variety of sources in a single running
service.

> **Running this as a customer demo?** See the [Demo Guide](docs/demo-guide.md) for the full setup walkthrough, architecture diagram, collector configuration, and DQL queries.

## How it works

Every `METRICS_INTERVAL_S` seconds the app samples three Gaussian-distributed metrics:

| Metric       | Distribution | Range      |
|--------------|--------------|------------|
| CPU usage    | N(50, 12)    | [0, 100] % |
| Memory usage | N(65, 10)    | [0, 100] % |
| Latency      | N(140, 35)   | [0, ∞) ms  |

Each tick those values are dispatched over four channels:

| Channel               | Direction                | Metric names              | Protocol                |
|-----------------------|--------------------------|---------------------------|-------------------------|
| **Prometheus scrape** | Pull — scraped on demand | `cn_prom_app_cpu/mem/lat` | HTTP GET `/metrics`     |
| **OTEL metrics**      | Push — every 30 s        | `cn_otel_cpu/mem/lat`     | OTLP HTTP `/v1/metrics` |
| **OTEL logs**         | Push — batched           | `cn_otel_log_cpu/mem/lat` | OTLP HTTP `/v1/logs`    |
| **Syslog**            | Push — every tick        | `cn_syslog_cpu/mem/lat`   | UDP RFC 5424            |

All four channels carry the same underlying values under different names so each ingestion path is
independently visible in Dynatrace.

## Prerequisites

- JDK 21
- sbt 1.x
- Docker (for containerised deployment)
- A Dynatrace tenant
- A Dynatrace OTEL Collector reachable from the app, configured to forward to Dynatrace (handles OTLP metrics, OTLP logs, syslog, and Prometheus scraping)

## Configuration

All configuration is via environment variables. Everything has a sensible default when running locally without a collector.

| Variable                      | Required | Default                 | Description                                                                             |
|-------------------------------|----------|-------------------------|-----------------------------------------------------------------------------------------|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | No       | `http://localhost:4318` | Base URL of the OTEL collector; `/v1/metrics` and `/v1/logs` are appended automatically |
| `SYSLOG_HOST`                 | No       | `127.0.0.1`             | Hostname or IP of the syslog receiver                                                   |
| `SYSLOG_PORT`                 | No       | `1514`                  | UDP port of the syslog receiver                                                         |
| `HTTP_PORT`                   | No       | `8080`                  | Port for the Prometheus scrape endpoint and health checks                               |
| `PROMETHEUS_PATH`             | No       | `/metrics`              | Path for the Prometheus scrape endpoint                                                 |
| `METRICS_INTERVAL_S`          | No       | `1`                     | Tick interval in seconds (must be > 0)                                                  |
| `APP_NAME`                    | No       | `dt-metrics-demo`       | Service name reported in all telemetry                                                  |
| `HOST_NAME`                   | No       | `demo-node`             | Host name reported in syslog messages                                                   |

## Building

```bash
sbt assembly
```

Produces `target/scala-3.4.2/dtmetrics-demo-assembly-0.1.0.jar`.

## Running locally

```bash
sbt run
```

The app starts and immediately begins ticking. Prometheus metrics are available at
`http://localhost:8080/metrics`. Health endpoints are at `http://localhost:8080/health/ready` and
`http://localhost:8080/health/live`.

With default settings the OTEL collector and syslog receiver are expected at `localhost`. If you do
not have them running locally you will see log lines like `[SYSLOG] failed to send` — the app
continues running regardless.

## Docker

Build the image:

```bash
docker build -t dt-metrics-demo:latest .
```

Run it:

```bash
docker run --rm \
  -e OTEL_EXPORTER_OTLP_ENDPOINT=http://host.docker.internal:4318 \
  -e SYSLOG_HOST=host.docker.internal \
  -p 8080:8080 \
  dt-metrics-demo:latest
```

## Kubernetes

See the [Demo Guide](docs/demo-guide.md) for the full Kubernetes setup including the OTEL collector, secrets, and manifests.

Quick reference — create the API token secret and apply all manifests:

```bash
kubectl create secret generic dt-secrets \
  --from-literal=dt-api-token=dt0c01.XXXX... \
  -n dt-demo

kubectl apply -f k8s/
```

The stack expects Kubernetes (tested on Rancher Desktop / k3s). All components run in the `dt-demo` namespace.

## HTTP endpoints

| Path                                  | Description                                                                               |
|---------------------------------------|-------------------------------------------------------------------------------------------|
| `GET /metrics` (or `PROMETHEUS_PATH`) | Prometheus text format — `cn_prom_app_*` gauges                                           |
| `GET /trigger/memory?duration=<min>`  | Override memory metric to ~95–100% for `duration` minutes (default 10); ignored if active |
| `GET /health/ready`                   | Readiness probe — returns `{"status":"ready"}`                                            |
| `GET /health/live`                    | Liveness probe — returns `{"status":"alive"}`                                             |

## Finding the metrics in Dynatrace

| Dynatrace feature | What to look for                                                              |
|-------------------|-------------------------------------------------------------------------------|
| Metrics explorer  | `cn_otel_cpu`, `cn_otel_mem`, `cn_otel_lat` (OTEL push)                      |
| Metrics explorer  | `cn_prom_app_cpu`, `cn_prom_app_mem`, `cn_prom_app_lat` (Prometheus scrape)  |
| Log viewer / DQL  | Messages containing `cn_otel_log_*` (OTEL log ingest)                        |
| Log viewer / DQL  | Messages containing `cn_syslog_*` (syslog ingest)                            |
