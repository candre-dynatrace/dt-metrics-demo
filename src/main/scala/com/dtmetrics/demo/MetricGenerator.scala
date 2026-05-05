package com.dtmetrics.demo

import java.util.concurrent.ThreadLocalRandom
import zio._

object MetricGenerator {

  /** Generate one tick of gaussian-distributed metrics.
    *
    *   cpu  ~ N(50,  12)
    *   mem  ~ N(65,  10)
    *   lat  ~ N(140, 35)
    */
  def tick: ZIO[Any, Nothing, Tick] = ZIO.succeed {
    val r = ThreadLocalRandom.current()
    val g: (Double, Double) => Double = (mu: Double, sigma: Double) => {
      val u1 = r.nextDouble(Double.MinPositiveValue, 1.0)
      val u2 = r.nextDouble()
      mu + sigma * Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2)
    }

    Tick(
      cpu = Math.max(0, Math.min(100, g(50, 12))),
      mem = Math.max(0, Math.min(100, g(65, 10))),
      lat = Math.max(0, g(140, 35))
    )
  }
}
