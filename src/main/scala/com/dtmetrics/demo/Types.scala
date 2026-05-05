package com.dtmetrics.demo

/** One tick of metric values (gaussian random each second). */
final case class Tick(cpu: Double, mem: Double, lat: Double) {
  def toPrometheus: String = {
    val cpuStr = String.format("%.3f", cpu)
    val memStr = String.format("%.3f", mem)
    val latStr = String.format("%.3f", lat)
    "# HELP app_cpu Application CPU usage percent\n" +
      "# TYPE app_cpu gauge\n" +
      "app_cpu " + cpuStr + "\n" +
      "# HELP app_mem Application memory usage percent\n" +
      "# TYPE app_mem gauge\n" +
      "app_mem " + memStr + "\n" +
      "# HELP app_lat Application latency milliseconds\n" +
      "# TYPE app_lat gauge\n" +
      "app_lat " + latStr + "\n"
  }
}
