package com.dtmetrics.demo

import io.opentelemetry.api.common.{AttributeKey, Attributes}
import io.opentelemetry.api.metrics.DoubleGauge
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.`export`.BatchLogRecordProcessor
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.`export`.PeriodicMetricReader
import io.opentelemetry.sdk.resources.Resource
import zio._

import java.time.Duration as JDuration

final case class OtelGauges(cpu: DoubleGauge, mem: DoubleGauge, lat: DoubleGauge)

object OtelClient {

  def scoped(config: DemoConfig): ZIO[Scope, Throwable, OtelGauges] =
    ZIO.acquireRelease(ZIO.attempt(build(config))) { case (sdk, _) =>
      ZIO.succeed(sdk.close())
    }.map(_._2)

  private def build(config: DemoConfig): (OpenTelemetrySdk, OtelGauges) = {
    val resource = Resource.getDefault.merge(
      Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), config.serviceName))
    )

    val meterProvider = SdkMeterProvider.builder()
      .registerMetricReader(
        PeriodicMetricReader.builder(
          OtlpHttpMetricExporter.builder()
            .setEndpoint(s"${config.otlpEndpoint}/v1/metrics")
            .build()
        ).setInterval(JDuration.ofSeconds(30)).build()
      )
      .setResource(resource)
      .build()

    val loggerProvider = SdkLoggerProvider.builder()
      .addLogRecordProcessor(
        BatchLogRecordProcessor.builder(
          OtlpHttpLogRecordExporter.builder()
            .setEndpoint(s"${config.otlpEndpoint}/v1/logs")
            .build()
        ).build()
      )
      .setResource(resource)
      .build()

    val sdk = OpenTelemetrySdk.builder()
      .setMeterProvider(meterProvider)
      .setLoggerProvider(loggerProvider)
      .build()

    OpenTelemetryAppender.install(sdk)

    val meter  = sdk.getMeter(config.serviceName)
    val gauges = OtelGauges(
      cpu = meter.gaugeBuilder("cn_app_cpu").setDescription("Application CPU usage percent").setUnit("%").build(),
      mem = meter.gaugeBuilder("cn_app_mem").setDescription("Application memory usage percent").setUnit("%").build(),
      lat = meter.gaugeBuilder("cn_app_lat").setDescription("Application latency").setUnit("ms").build()
    )

    (sdk, gauges)
  }
}
