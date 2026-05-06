# dt-metrics-demo

A Scala/ZIO demo application that continuously generates synthetic CPU, memory, and latency metrics and
ships them to Dynatrace via four independent ingestion channels simultaneously. Its purpose is to
demonstrate how Dynatrace can receive observability data from a variety of sources in a single running
service.

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
- An OTEL collector reachable from the app, configured to forward to Dynatrace
- A syslog receiver reachable from the app (e.g. Dynatrace ActiveGate with syslog ingest, or Fluent Bit)

## Configuration

All configuration is via environment variables. `DT_TENANT_URL` and `DT_API_TOKEN` are required;
everything else has a default.

| Variable                      | Required | Default                 | Description                                                                             |
|-------------------------------|----------|-------------------------|-----------------------------------------------------------------------------------------|
| `DT_TENANT_URL`               | Yes      | —                       | Dynatrace tenant hostname, e.g. `abc12345.live.dynatrace.com`                           |
| `DT_API_TOKEN`                | Yes      | —                       | Dynatrace API token with metrics ingest scope                                           |
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
export DT_TENANT_URL=abc12345.live.dynatrace.com
export DT_API_TOKEN=dt0c01.XXXX...

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
  -e DT_TENANT_URL=abc12345.live.dynatrace.com \
  -e DT_API_TOKEN=dt0c01.XXXX... \
  -e OTEL_EXPORTER_OTLP_ENDPOINT=http://host.docker.internal:4318 \
  -e SYSLOG_HOST=host.docker.internal \
  -p 8080:8080 \
  dt-metrics-demo:latest
```

## Kubernetes

Create the secret first:

```bash
kubectl create secret generic dt-secrets \
  --from-literal=tenant-url=abc12345.live.dynatrace.com \
  --from-literal=dt-api-token=dt0c01.XXXX...
```

Then apply the manifests:

```bash
kubectl apply -f k8s/deployment.yaml
```

The deployment expects:

- An OTEL collector Service named `otel-collector` in the `monitoring` namespace, accepting OTLP HTTP on port 4318
- A syslog receiver Service named `dt-log-ingest` in the `monitoring` namespace, accepting UDP RFC 5424 on port 1514

The app is exposed within the cluster as a `ClusterIP` Service on port 8080. To scrape Prometheus
metrics from outside the cluster, configure your scraper to target that Service.

## HTTP endpoints

| Path                                  | Description                                     |
|---------------------------------------|-------------------------------------------------|
| `GET /metrics` (or `PROMETHEUS_PATH`) | Prometheus text format — `cn_prom_app_*` gauges |
| `GET /health/ready`                   | Readiness probe — returns `{"status":"ready"}`  |
| `GET /health/live`                    | Liveness probe — returns `{"status":"alive"}`   |

## Finding the metrics in Dynatrace

| Dynatrace feature | What to look for                                                                                                    |
|-------------------|---------------------------------------------------------------------------------------------------------------------|
| Metrics explorer  | `cn_otel_cpu`, `cn_otel_mem`, `cn_otel_lat` (OTEL push)                                                             |
| Metrics explorer  | `cn_prom_app_cpu`, `cn_prom_app_mem`, `cn_prom_app_lat` (Prometheus scrape, once ActiveGate scraping is configured) |
| Log viewer        | Messages containing `cn_otel_log_cpu`, `cn_otel_log_mem`, `cn_otel_log_lat` (OTEL log ingest)                       |
| Log viewer        | Messages containing `cn_syslog_cpu`, `cn_syslog_mem`, `cn_syslog_lat` (syslog ingest)                               |
