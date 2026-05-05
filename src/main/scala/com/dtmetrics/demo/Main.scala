package com.dtmetrics.demo

import zio.{Unsafe, _}
import com.sun.net.httpserver.{HttpExchange, HttpServer, HttpHandler}
import org.slf4j.{Logger, LoggerFactory}
import java.net.{DatagramSocket, InetSocketAddress}

object Main extends ZIOAppDefault {

  private val logger: Logger = LoggerFactory.getLogger("com.dtmetrics.demo")

  // Helper to start an HTTP server in a daemon thread
  private def startHttpServer(port: Int, routes: List[(String, HttpHandler)]): Unit = {
    val server = HttpServer.create(new InetSocketAddress(port), 0)
    routes.foreach { case (path, handler) =>
      server.createContext(path, handler)
    }
    server.start()
    logger.info("[HTTP] started on port " + port)
  }

  private val appRun: ZIO[Any, Throwable, Unit] =
    ZIO.acquireReleaseWith(ZIO.attempt(new DatagramSocket()))(s => ZIO.succeed(s.close())) { sock =>
      for {
        config  <- DemoConfig.load
        tickRef <- Ref.make(Tick(0.0, 0.0, 0.0))

        // Start Prometheus HTTP (daemon thread)
        _ <- ZIO.attempt {
          startHttpServer(
            config.prometheusPort,
            List(
              (
                config.prometheusPath,
                (he: HttpExchange) => {
                  Unsafe.unsafe { implicit u =>
                    Runtime.default.unsafe.run(
                      tickRef.get.flatMap { tick =>
                        ZIO.attempt {
                          val body = tick.toPrometheus.getBytes("UTF-8")
                          he.getResponseHeaders.set("Content-Type", "text/plain; version=0.0.4")
                          he.sendResponseHeaders(200, body.length)
                          he.getResponseBody.write(body)
                          he.getResponseBody.close()
                        }
                      }
                    )
                  }
                }
              )
            )
          )
          ZIO.logInfo(s"[PROMETHEUS] http://0.0.0.0:${config.prometheusPort}${config.prometheusPath}")
        }.orDie

        // Start health HTTP (daemon thread) on fixed port 8080
        _ <- ZIO.attempt {
          val svcPort = 8080
          startHttpServer(
            svcPort,
            List(
              (
                "/health/ready",
                (he: HttpExchange) => {
                  val b = """{"status":"ready"}""".getBytes
                  he.sendResponseHeaders(200, b.length)
                  he.getResponseBody.write(b)
                  he.close()
                }
              ),
              (
                "/health/live",
                (he: HttpExchange) => {
                  val b = """{"status":"alive"}""".getBytes
                  he.sendResponseHeaders(200, b.length)
                  he.getResponseBody.write(b)
                  he.close()
                }
              )
            )
          )
          ZIO.logInfo(s"[HEALTH] http://0.0.0.0:${svcPort}/health/")
        }

        // Tick loop (foreground, runs forever)
        tickDur = zio.Duration.fromSeconds(config.intervalSec)
        _ <- MetricGenerator.tick
          .flatMap { tick =>
            for {
              _ <- tickRef.set(tick)
              _ <- ZIO.logInfo(s"[TICK] cpu=${tick.cpu} mem=${tick.mem} lat=${tick.lat}")
              _ <- MetricExporter.toDynatrace(tick, config)
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
