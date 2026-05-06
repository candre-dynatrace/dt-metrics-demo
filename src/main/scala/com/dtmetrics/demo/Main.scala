package com.dtmetrics.demo

import zio.{Unsafe, _}
import com.sun.net.httpserver.{HttpExchange, HttpServer, HttpHandler}
import java.net.{DatagramSocket, InetSocketAddress}

object Main extends ZIOAppDefault {

  private def startHttpServer(port: Int, routes: List[(String, HttpHandler)]): ZIO[Any, Throwable, Unit] =
    ZIO.attempt {
      val server = HttpServer.create(new InetSocketAddress(port), 0)
      routes.foreach { case (path, handler) =>
        server.createContext(path, handler)
      }
      server.start()
    } *> ZIO.logInfo(s"HTTP server started on port $port")

  private val appRun: ZIO[Any, Throwable, Unit] =
    ZIO.scoped {
      for {
        sock    <- ZIO.acquireRelease(ZIO.attempt(new DatagramSocket()))(s => ZIO.succeed(s.close()))
        config  <- DemoConfig.load
        gauges  <- OtelClient.scoped(config)
        tickRef <- Ref.make(Tick(0.0, 0.0, 0.0))

        _ <- startHttpServer(config.httpPort, List(
          config.prometheusPath -> { (he: HttpExchange) =>
            Unsafe.unsafe { implicit u =>
              Runtime.default.unsafe.run {
                tickRef.get.flatMap { tick =>
                  ZIO.attempt {
                    val body = tick.toPrometheus.getBytes("UTF-8")
                    he.getResponseHeaders.set("Content-Type", "text/plain; version=0.0.4")
                    he.sendResponseHeaders(200, body.length)
                    he.getResponseBody.write(body)
                    he.getResponseBody.close()
                  }
                }
              }
            }
          },
          "/health/ready" -> { (he: HttpExchange) =>
            val b = """{"status":"ready"}""".getBytes
            he.sendResponseHeaders(200, b.length)
            he.getResponseBody.write(b)
            he.close()
          },
          "/health/live" -> { (he: HttpExchange) =>
            val b = """{"status":"alive"}""".getBytes
            he.sendResponseHeaders(200, b.length)
            he.getResponseBody.write(b)
            he.close()
          }
        ))
        _ <- ZIO.logInfo(s"Listening on http://0.0.0.0:${config.httpPort}")
        _ <- ZIO.logInfo(s"  Prometheus : ${config.prometheusPath}")
        _ <- ZIO.logInfo(s"  Health     : /health/{ready, live}")

        tickDur = zio.Duration.fromSeconds(config.intervalSec)
        _ <- MetricGenerator.tick
          .flatMap { tick =>
            for {
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

  def run: ZIO[Any, Throwable, ExitCode] =
    appRun.as(ExitCode.success)
}
