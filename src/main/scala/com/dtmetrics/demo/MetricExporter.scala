package com.dtmetrics.demo

import zio._

object MetricExporter {

  def toDynatrace(tick: Tick, gauges: OtelGauges): ZIO[Any, Throwable, Unit] =
    ZIO.attempt {
      gauges.cpu.set(tick.cpu)
      gauges.mem.set(tick.mem)
      gauges.lat.set(tick.lat)
    } *> ZIO.logDebug(s"[DT-METRIC] recorded cpu=${tick.cpu} mem=${tick.mem} lat=${tick.lat}")
}
