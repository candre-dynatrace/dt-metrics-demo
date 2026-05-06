# dt-metrics-demo — Claude context

A Scala/ZIO demo application that ships synthetic metrics and logs to Dynatrace via four independent signal paths, all routed through a single Dynatrace OTEL Collector running in Kubernetes.

See `README.md` for the feature overview and `docs/demo-guide.md` for the full customer walkthrough.

## Signal paths

| Signal | Metric/log names | Protocol | Where configured |
|--------|-----------------|----------|-----------------|
| OTEL metrics | `cn_otel_cpu/mem/lat` | OTLP HTTP push every 30 s | `OtelClient.scala` → `PeriodicMetricReader` |
| OTEL logs | body contains `cn_otel_log_cpu/mem/lat` | OTLP HTTP via logback appender | `OtelClient.scala` → `BatchLogRecordProcessor` + `logback.xml` |
| Syslog | body contains `cn_syslog_cpu/mem/lat` | RFC 5424 UDP to collector port 1514 | `LogExporter.scala` |
| Prometheus | `cn_prom_app_cpu/mem/lat` | HTTP GET `/metrics` scraped by collector | `Types.scala` → `Tick.toPrometheus` |

The app does **not** talk to Dynatrace directly. All signals go through the OTEL Collector (`k8s/02-otel-collector.yaml`).

## Source map

```
src/main/scala/com/dtmetrics/demo/
  Main.scala            — ZIOApp entry point; HTTP server; tick loop; memory-pressure trigger
  DemoConfig.scala      — Environment variable config (all optional, all have defaults)
  MetricGenerator.scala — Gaussian random sampling for cpu/mem/lat
  MetricExporter.scala  — Writes sampled values into the OTEL gauges (cn_otel_*)
  OtelClient.scala      — Builds OTel SDK: MeterProvider + LoggerProvider + installs logback appender
  LogExporter.scala     — ZIO.logInfo for OTEL logs; UDP datagram for syslog
  Types.scala           — Tick case class + toPrometheus; ProblemTrigger
  ProblemTrigger.scala  — ADT: Idle | MemPressure(expiry)

src/main/resources/
  logback.xml           — ConsoleAppender + OpenTelemetryAppender (routes SLF4J logs to OTEL SDK)

k8s/
  00-namespace.yaml     — dt-demo namespace
  01-otel-collector-cm.yaml — Collector config: syslog + prometheus + otlp receivers, otlphttp exporter
  02-otel-collector.yaml    — Collector Deployment + ClusterIP services (otel-collector:4318, dt-log-ingest:1514)
  04-app.yaml           — App Deployment + ClusterIP service (dt-metrics-demo:8080)
```

## Build and deploy

```bash
# Build fat JAR and Docker image
sbt assembly
docker build -t dt-metrics-demo:latest .

# First-time cluster setup
kubectl apply -f k8s/00-namespace.yaml
kubectl create secret generic dt-secrets \
  --from-literal=dt-api-token=<token> \
  -n dt-demo
kubectl apply -f k8s/

# After a code change
docker build -t dt-metrics-demo:latest .
kubectl rollout restart deployment/dt-metrics-demo -n dt-demo

# Verify
kubectl get pods -n dt-demo
kubectl logs -n dt-demo deploy/dt-metrics-demo --tail=30
kubectl logs -n dt-demo deploy/otel-collector --tail=30
```

## Collector endpoint

Edit `k8s/01-otel-collector-cm.yaml` and set:

```yaml
exporters:
  otlphttp/dynatrace:
    endpoint: "https://<env-id>.live.dynatrace.com/api/v2/otlp"
    headers:
      Authorization: "Api-Token ${env:DT_API_TOKEN}"
```

The API token needs `metrics.ingest` and `logs.ingest` scopes.

## Critical gotchas

### ZIO logging must be bridged to SLF4J

`ZIOAppDefault` uses ZIO's built-in console logger by default — it never touches SLF4J or logback, so the `OpenTelemetryAppender` in `logback.xml` is never invoked and OTEL logs are never exported.

The fix (already in `Main.scala`):

```scala
override val bootstrap: ZLayer[Any, Nothing, Unit] =
  Runtime.removeDefaultLoggers >>> SLF4J.slf4j
```

Without this, `ZIO.logInfo(...)` calls produce console output but **nothing reaches Dynatrace**.

### Syslog timestamps must have exactly 6 fractional-second digits

The OTel syslog receiver (`protocol: rfc5424`) requires RFC 3339 with microsecond precision. Java's `Instant.toString()` produces variable precision (3 digits for whole milliseconds) and causes a parse error at the collector.

The fix (already in `LogExporter.scala`):

```scala
private val rfc5424Ts =
  DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSX").withZone(ZoneOffset.UTC)
// ...
val ts = rfc5424Ts.format(Instant.now())
```

### No ActiveGate — all signals through the OTEL Collector

The architecture was originally designed with a Dynatrace ActiveGate, but containerised ActiveGates require registration approval from the Dynatrace backend and are rejected on sprint/trial tenants. The OTEL Collector handles everything: syslog ingestion, Prometheus scraping, and forwarding OTLP signals. This is the simpler and more portable approach.

### k8s file numbering gap

The manifests are numbered `00`, `01`, `02`, `04` — there is no `03`. File `03-activegate.yaml` was removed when the ActiveGate approach was dropped. `kubectl apply -f k8s/` works fine; the gap is intentional.
