import sbtbuildinfo.BuildInfoPlugin.autoImport.BuildInfoKey

ThisBuild / scalaVersion     := "3.4.2"
ThisBuild / organization     := "com.dtmetrics"
ThisBuild / organizationName := "DT Metrics Demo"

lazy val buildTime = System.currentTimeMillis()

// Logging deps from FunKlaw
val zioLogging = "dev.zio"       %% "zio-logging-slf4j" % "2.5.0"
val logback    = "ch.qos.logback" % "logback-classic"   % "1.5.18"
val julToSlf4j = "org.slf4j"      % "jul-to-slf4j"      % "2.0.16"

// OpenTelemetry — metrics + logs only (no tracing), versions from FunKlaw
val otelVersion      = "1.44.1"
val otelInstrVersion = "2.11.0-alpha"
val otelApi          = "io.opentelemetry"                 % "opentelemetry-api"                  % otelVersion
val otelSdk          = "io.opentelemetry"                 % "opentelemetry-sdk"                  % otelVersion
val otelOtlp         = "io.opentelemetry"                 % "opentelemetry-exporter-otlp"        % otelVersion
val otelLogback      = "io.opentelemetry.instrumentation" % "opentelemetry-logback-appender-1.0" % otelInstrVersion

lazy val root = (project in file("."))
  .enablePlugins(BuildInfoPlugin)
  .settings(
    name          := "dtmetrics-demo",
    version       := "0.1.0",
    buildInfoKeys := Seq[BuildInfoKey](name, version, "buildTime" -> buildTime),
    buildInfoOptions += BuildInfoOption.BuildTime,
    Compile / run / fork := true,
    Test / fork          := true,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", _*) => MergeStrategy.discard
      case _                        => MergeStrategy.first
    },
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % "2.1.25",
      zioLogging,
      logback,
      julToSlf4j,
      otelApi,
      otelSdk,
      otelOtlp,
      otelLogback
    )
  )

addCommandAlias("fmt", "all scalafmtSbt scalafmt Test/scalafmt")
addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck Test/scalafmtCheck")
