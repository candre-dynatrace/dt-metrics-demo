package com.dtmetrics.demo

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import zio.{Unsafe, *}

import java.net.{DatagramSocket, InetSocketAddress}
import java.nio.charset.StandardCharsets

object Main extends ZIOAppDefault {

  private def respond(he: HttpExchange, body: Array[Byte], contentType: String): Unit = {
    he.getResponseHeaders.set("Content-Type", contentType)
    he.sendResponseHeaders(200, body.length)
    he.getResponseBody.write(body)
    he.getResponseBody.close()
  }

  private def staticHandler(body: String): HttpHandler = { (he: HttpExchange) =>
    respond(he, body.getBytes(StandardCharsets.UTF_8), "application/json")
  }

  private def prometheusHandler(tickRef: Ref[Tick]): HttpHandler = { (he: HttpExchange) =>
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run {
        tickRef.get.flatMap { tick =>
          ZIO.attempt(respond(he, tick.toPrometheus.getBytes(StandardCharsets.UTF_8), "text/plain; version=0.0.4"))
        }
      }
    }
  }

  private def parseQueryParam(query: String, key: String): Option[String] =
    Option(query).toSeq
      .flatMap(_.split("&").toSeq)
      .map(_.split("=", 2))
      .collectFirst { case Array(k, v) if k == key => v }

  private def triggerMemoryHandler(triggerRef: Ref[ProblemTrigger]): HttpHandler = { (he: HttpExchange) =>
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run {
        for {
          now <- ZIO.succeed(java.time.Instant.now())
          rawParam = parseQueryParam(he.getRequestURI.getQuery, "duration")
          minutes  = rawParam.flatMap(_.toIntOption).getOrElse(10)
          body <- triggerRef.modify {
            case ProblemTrigger.MemPressure(exp) if now.isBefore(exp) =>
              (s"""{"status":"already_active","expiresAt":"$exp"}""", ProblemTrigger.MemPressure(exp))
            case _ =>
              val exp = now.plusSeconds(minutes.toLong * 60)
              (s"""{"status":"triggered","expiresAt":"$exp"}""", ProblemTrigger.MemPressure(exp))
          }
          _ <- ZIO.attempt(respond(he, body.getBytes(StandardCharsets.UTF_8), "application/json"))
        } yield ()
      }
    }
  }

  private def startHttpServer(port: Int, routes: List[(String, HttpHandler)]): ZIO[Any, Throwable, Unit] =
    ZIO.attempt {
      val server = HttpServer.create(new InetSocketAddress(port), 0)
      routes.foreach { case (path, handler) =>
        server.createContext(path, handler)
      }
      server.start()
    } *> ZIO.logInfo(s"HTTP server started on port $port")

  def run: ZIO[Any, Throwable, Unit] =
    ZIO.scoped {
      for {
        sock       <- ZIO.acquireRelease(ZIO.attempt(new DatagramSocket()))(s => ZIO.succeed(s.close()))
        config     <- DemoConfig.load
        gauges     <- OtelClient.scoped(config)
        tickRef    <- Ref.make(Tick(0.0, 0.0, 0.0))
        triggerRef <- Ref.make[ProblemTrigger](ProblemTrigger.Idle)

        _ <- startHttpServer(
          config.httpPort,
          List(
            config.prometheusPath -> prometheusHandler(tickRef),
            "/trigger/memory"     -> triggerMemoryHandler(triggerRef),
            "/health/ready"       -> staticHandler("""{"status":"ready"}"""),
            "/health/live"        -> staticHandler("""{"status":"alive"}""")
          )
        )
        _ <- ZIO.logInfo(s"Listening on http://0.0.0.0:${config.httpPort}")
        _ <- ZIO.logInfo(s"  Prometheus : ${config.prometheusPath}")
        _ <- ZIO.logInfo(s"  Trigger    : /trigger/memory?duration=<minutes>")
        _ <- ZIO.logInfo(s"  Health     : /health/{ready, live}")

        tickDur = zio.Duration.fromSeconds(config.intervalSec)
        _ <- MetricGenerator.tick
          .flatMap { rawTick =>
            for {
              trigger <- triggerRef.get
              now     <- ZIO.succeed(java.time.Instant.now())
              mem <- trigger match {
                case ProblemTrigger.MemPressure(exp) if now.isBefore(exp) =>
                  MetricGenerator.memUnderPressure
                case ProblemTrigger.MemPressure(_) =>
                  triggerRef.set(ProblemTrigger.Idle).as(rawTick.mem)
                case ProblemTrigger.Idle =>
                  ZIO.succeed(rawTick.mem)
              }
              tick = rawTick.copy(mem = mem)
              _ <- tickRef.set(tick)
              _ <- ZIO.logInfo(s"[TICK] cpu=${tick.cpu} mem=${tick.mem} lat=${tick.lat}")
              _ <- MetricExporter.toDynatrace(tick, gauges)
              _ <- LogExporter.emit(tick, config, sock)
            } yield ()
          }
          .repeat(Schedule.fixed(tickDur))
          .orDie

      } yield ()
    }
}
