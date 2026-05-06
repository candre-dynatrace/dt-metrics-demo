package com.dtmetrics.demo

import java.time.Instant

sealed trait ProblemTrigger

object ProblemTrigger {
  case object Idle                                 extends ProblemTrigger
  final case class MemPressure(expiresAt: Instant) extends ProblemTrigger
}
