package com.dtmetrics.demo

import zio._

object MetricGenerator {

  /** Generate one tick of gaussian-distributed metrics.
    *
    *   cpu  ~ N(50,  12)   clipped to [0, 100]
    *   mem  ~ N(65,  10)   clipped to [0, 100]
    *   lat  ~ N(140, 35)   clipped to [0, ∞)
    */
  def tick: ZIO[Any, Nothing, Tick] =
    for {
      cpu <- Random.nextGaussian.map(g => (50.0 + 12.0 * g).max(0.0).min(100.0))
      mem <- Random.nextGaussian.map(g => (65.0 + 10.0 * g).max(0.0).min(100.0))
      lat <- Random.nextGaussian.map(g => (140.0 + 35.0 * g).max(0.0))
    } yield Tick(cpu, mem, lat)
}
