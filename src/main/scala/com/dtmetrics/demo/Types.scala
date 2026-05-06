package com.dtmetrics.demo

/** One tick of metric values (gaussian random each second). */
final case class Tick(cpu: Double, mem: Double, lat: Double) {
  def toPrometheus: String = {
    val cpuStr = String.format("%.3f", cpu)
    val memStr = String.format("%.3f", mem)
    val latStr = String.format("%.3f", lat)
    "# HELP cn_prom_app_cpu Application CPU usage percent\n" +
      "# TYPE cn_prom_app_cpu gauge\n" +
      "cn_prom_app_cpu " + cpuStr + "\n" +
      "# HELP cn_prom_app_mem Application memory usage percent\n" +
      "# TYPE cn_prom_app_mem gauge\n" +
      "cn_prom_app_mem " + memStr + "\n" +
      "# HELP cn_prom_app_lat Application latency milliseconds\n" +
      "# TYPE cn_prom_app_lat gauge\n" +
      "cn_prom_app_lat " + latStr + "\n"
  }
}
