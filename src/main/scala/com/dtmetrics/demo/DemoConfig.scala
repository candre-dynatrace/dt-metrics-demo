package com.dtmetrics.demo

import zio.*

import scala.util.Try

final case class DemoConfig(
    httpPort: Int,
    prometheusPath: String,
    otlpEndpoint: String,
    dtTenantUrl: String,
    dtApiToken: String,
    syslogHost: String,
    syslogPort: Int,
    intervalSec: Int,
    serviceName: String,
    hostName: String
)

object DemoConfig {
  private val env   = (key: String) => Option(java.lang.System.getenv(key))
  private val str   = (key: String, d: String) => env(key).getOrElse(d)
  private val intOr = (key: String, d: Int) => env(key).flatMap(v => Try(v.toInt).toOption).getOrElse(d)

  val load: ZIO[Any, Throwable, DemoConfig] = ZIO.attempt {
    val tenantUrl = env("DT_TENANT_URL")
      .getOrElse(throw new RuntimeException("DT_TENANT_URL required"))
    val token = env("DT_API_TOKEN")
      .getOrElse(throw new RuntimeException("DT_API_TOKEN required"))

    DemoConfig(
      httpPort = intOr("HTTP_PORT", 8080),
      prometheusPath = str("PROMETHEUS_PATH", "/metrics"),
      otlpEndpoint = str("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4318"),
      dtTenantUrl = "https://" + tenantUrl.stripPrefix("https://").stripPrefix("http://").stripSuffix("/") + "/api/v2/metrics/otlp",
      dtApiToken = token,
      syslogHost = str("SYSLOG_HOST", "127.0.0.1"),
      syslogPort = intOr("SYSLOG_PORT", 1514),
      intervalSec = {
        val s = intOr("METRICS_INTERVAL_S", 1)
        if (s > 0) s else throw new IllegalArgumentException(s"METRICS_INTERVAL_S must be > 0, got $s")
      },
      serviceName = str("APP_NAME", "dt-metrics-demo"),
      hostName = str("HOST_NAME", "demo-node")
    )
  }
}
