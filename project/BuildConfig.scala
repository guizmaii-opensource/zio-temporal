import sbt.Keys.scalaVersion
import sbt._

object BuildConfig extends Dependencies {

  val baseLibs = Seq(
    Temporal.self,
    Temporal.openTracing % Optional,
    Zio.self,
    Jackson.scala,
    Jackson.jsr310
  )

  val coreLibs = baseLibs ++ Seq(
    Zio.streams,
    Utility.scalaJava8Compat,
    Testing.scalatest
  )

  val testkitLibs = baseLibs ++ Seq(
    Temporal.testing
  )

  val testLibs = baseLibs ++ Seq(
    Zio.test,
    Zio.testSbt,
    Zio.testMagnolia,
    Zio.prelude,
    Logging.zio,
    Logging.zioSlf4j,
    Logging.logback,
    Testing.scalatest
  ).map(_ % Test)

  val protobufLibs = baseLibs ++ Seq(
    Scalapb.runtime,
    Scalapb.runtimeProtobuf,
    Testing.scalatest
  )

  val examplesLibs = baseLibs ++ Seq(
    Zio.cli,
    Logging.zio,
    Logging.zioSlf4j,
    Logging.logback,
    Temporal.openTracing,
    Monitoring.micrometerOtlp
  ) ++ Monitoring.otel

  val docsLibs = baseLibs ++ examplesLibs ++ Seq(Zio.test)
}

trait Dependencies {

  private object versions {
    val temporal   = "1.32.0"
    val zio        = "2.1.22"
    val zioLogging = "2.5.1"
    val zioPrelude = "1.0.0-RC35"
    val enumeratum = "1.9.0"
    val jackson    = "2.20.1"
    val otel       = "1.56.0"
  }

  object Temporal {
    val self        = "io.temporal" % "temporal-sdk"         % versions.temporal
    val testing     = "io.temporal" % "temporal-testing"     % versions.temporal
    val openTracing = "io.temporal" % "temporal-opentracing" % versions.temporal
  }

  object Jackson {
    val scala = "com.fasterxml.jackson.module" %% "jackson-module-scala" % versions.jackson
    // to support zio.Duration (that is java.time.Duration)
    val jsr310 = "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % versions.jackson
  }

  object Zio {
    val self           = "dev.zio" %% "zio"               % versions.zio
    val streams        = "dev.zio" %% "zio-streams"       % versions.zio
    val test           = "dev.zio" %% "zio-test"          % versions.zio
    val testSbt        = "dev.zio" %% "zio-test-sbt"      % versions.zio
    val testMagnolia   = "dev.zio" %% "zio-test-magnolia" % versions.zio
    val prelude        = "dev.zio" %% "zio-prelude"       % versions.zioPrelude
    val testFrameworks = Seq(new TestFramework("zio.test.sbt.ZTestFramework"))

    // only for examples
    val cli = "dev.zio" %% "zio-cli" % "0.7.4"
  }

  object Scalapb {
    val runtime         = "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion
    val runtimeProtobuf = runtime                 % "protobuf"
  }

  object Utility {
    val scalaJava8Compat  = "org.scala-lang.modules" %% "scala-java8-compat"      % "1.0.2"
    val collectionsCompat = "org.scala-lang.modules" %% "scala-collection-compat" % "2.14.0"
  }

  object Logging {
    val zio      = "dev.zio"       %% "zio-logging"       % versions.zioLogging
    val zioSlf4j = "dev.zio"       %% "zio-logging-slf4j" % versions.zioLogging
    val logback  = "ch.qos.logback" % "logback-classic"   % "1.5.21"
  }

  object Testing {
    val scalatest = "org.scalatest" %% "scalatest" % "3.2.19"
  }

  object Monitoring {
    val otelApi              = "io.opentelemetry"         % "opentelemetry-api"                         % versions.otel
    val otelExporterOtlp     = "io.opentelemetry"         % "opentelemetry-exporter-otlp"               % versions.otel
    val otelTracePropagators = "io.opentelemetry"         % "opentelemetry-extension-trace-propagators" % versions.otel
    val otelOpentracingShim  = "io.opentelemetry"         % "opentelemetry-opentracing-shim"            % versions.otel
    val otelSemvonc          = "io.opentelemetry.semconv" % "opentelemetry-semconv"                     % "1.37.0"

    val otel = Seq(otelApi, otelExporterOtlp, otelTracePropagators, otelOpentracingShim, otelSemvonc)

    val micrometerOtlp = "io.micrometer" % "micrometer-registry-otlp" % "1.16.0"
  }
}
