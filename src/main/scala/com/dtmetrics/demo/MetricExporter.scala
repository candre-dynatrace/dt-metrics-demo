package com.dtmetrics.demo

import zio._

object MetricExporter {

  /** Send all three gauges to Dynatrace Metrics V2 ingest REST API (logs for demo). */
  def toDynatrace(tick: Tick, config: DemoConfig): ZIO[Any, Throwable, Unit] =
    for {
      metrics <- ZIO.attempt {
        val cpuStr = String.format("%.3f", tick.cpu)
        val memStr = String.format("%.3f", tick.mem)
        val latStr = String.format("%.3f", tick.lat)
        val tsNano = java.lang.System.currentTimeMillis() * 1000000L

        Seq(
          ("dtmetrics.app.cpu.percent", cpuStr),
          ("dtmetrics.app.mem.percent", memStr),
          ("dtmetrics.app.lat.ms", latStr)
        )
      }
      _ <- ZIO.foreach(metrics) { case (name, value) =>
        ZIO.logInfo(s"[DT-METRIC] target: ${config.dtTenantUrl}\n  $name=$value")
      }
      _ <- ZIO.logDebug(s"[DT-METRIC] sent 3 metrics to ${config.dtTenantUrl}")
    } yield ()
}
