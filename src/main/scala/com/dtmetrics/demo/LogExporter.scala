package com.dtmetrics.demo

import zio._
import java.net.{DatagramSocket, InetAddress, DatagramPacket}
import java.nio.charset.StandardCharsets
import java.time.Instant

object LogExporter {

  /** Emit log to Syslog UDP + OTel log collector (logs for demo). */
  def emit(tick: Tick, config: DemoConfig, sock: DatagramSocket): ZIO[Any, Throwable, Unit] =
    for {
      _ <- ZIO.logInfo(s"[OTEL-LOG] to ${config.otlpEndpoint}")
      _ <- ZIO.logInfo(
        s"[OTEL-LOG] tick: cn_otel_log_cpu=${tick.cpu} cn_otel_log_mem=${tick.mem} cn_otel_log_lat=${tick.lat} svc=${config.serviceName}"
      )
      _ <- ZIO
        .attempt {
          val ts = Instant.now().toString
          val msg =
            s"[INFO] tick: cn_syslog_cpu=${tick.cpu} cn_syslog_mem=${tick.mem} cn_syslog_lat=${tick.lat} svc=${config.serviceName}"
          val syslog = s"<14>1 $ts ${config.hostName} ${config.serviceName} ${config.serviceName} 1 - - $msg"
          val bytes  = syslog.getBytes(StandardCharsets.UTF_8)
          sock.send(new DatagramPacket(bytes, bytes.length, InetAddress.getByName(config.syslogHost), config.syslogPort))
        }
        .foldZIO(
          err => ZIO.logError(s"[SYSLOG] failed to send to ${config.syslogHost}:${config.syslogPort}: ${err.getMessage}"),
          _ => ZIO.logInfo(s"[SYSLOG] -> ${config.syslogHost}:${config.syslogPort}")
        )
    } yield ()
}
