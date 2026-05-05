package com.dtmetrics.demo

import zio._

final case class DemoConfig(
    otlpEndpoint: String,
    dtTenantUrl: String,
    dtApiToken: String,
    prometheusPort: Int,
    prometheusPath: String,
    syslogHost: String,
    syslogPort: Int,
    intervalSec: Int,
    serviceName: String,
    hostName: String
)

object DemoConfig {
  private val env   = (key: String) => Option(java.lang.System.getenv(key))
  private val str   = (key: String, d: String) => env(key).getOrElse(d)
  private val intOr = (key: String, d: Int) => env(key).map(_.toInt).getOrElse(d)

  val load: ZIO[Any, Throwable, DemoConfig] = ZIO.attempt {
    val tenantUrl = env("DT_TENANT_URL")
      .getOrElse(throw new RuntimeException("DT_TENANT_URL required"))
    val token = env("DT_API_TOKEN")
      .getOrElse(throw new RuntimeException("DT_API_TOKEN required"))

    DemoConfig(
      otlpEndpoint = str("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4318"),
      dtTenantUrl = "https://" + stripProtocol(tenantUrl).stripSuffix("/") + "/api/v2/metrics/otlp",
      dtApiToken = token,
      prometheusPort = intOr("PROMETHEUS_PORT", 9496),
      prometheusPath = str("PROMETHEUS_PATH", "/metrics"),
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

  private def stripProtocol(url: String): String =
    url.stripPrefix("https://").stripPrefix("http://")
}
