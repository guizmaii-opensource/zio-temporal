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

  val coreLibsScala2 = Seq(
    Utility.collectionsCompat,
    Enumeratum.enumeratum % Optional
  )

  val testkitLibs = baseLibs ++ Seq(
    Temporal.testing
  )

  val testLibs = baseLibs ++ Seq(
    Zio.test,
    Zio.testSbt,
    Zio.testMagnolia,
    Logging.zio,
    Logging.zioSlf4j,
    Logging.logback,
    Testing.scalatest
  ).map(_ % Test)

  val testLibsScala2 = Seq(
    Enumeratum.enumeratum % Test
  )

  val protobufLibs = baseLibs ++ Seq(
    Scalapb.runtime,
    Scalapb.runtimeProtobuf,
    Testing.scalatest
  )

  val protobufScala2Libs = Seq(
    Enumeratum.enumeratum % Optional
  )

  val examplesLibs = baseLibs ++ Seq(
    Zio.cli,
    Logging.zio,
    Logging.zioSlf4j,
    Logging.logback,
    Temporal.openTracing,
    Monitoring.micrometerOtlp
  ) ++ Monitoring.otel

  val docsLibs = baseLibs ++ examplesLibs ++ Seq(
    Zio.test,
    Enumeratum.enumeratum
  )
}

trait Dependencies {

  private object versions {
    val temporal   = "1.30.1"
    val zio        = "2.1.20"
    val zioLogging = "2.5.1"
    val enumeratum = "1.9.0"
    val jackson    = "2.19.2"
    val otel       = "1.52.0"
  }

  object org {
    val beachape = "com.beachape"
    val zio      = "dev.zio"
    val temporal = "io.temporal"
  }

  object Temporal {
    val self        = org.temporal % "temporal-sdk"         % versions.temporal
    val testing     = org.temporal % "temporal-testing"     % versions.temporal
    val openTracing = org.temporal % "temporal-opentracing" % versions.temporal
  }

  object Jackson {
    val scala = "com.fasterxml.jackson.module" %% "jackson-module-scala" % versions.jackson
    // to support zio.Duration (that is java.time.Duration)
    val jsr310 = "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % versions.jackson
  }

  object Zio {
    val self           = org.zio %% "zio"               % versions.zio
    val streams        = org.zio %% "zio-streams"       % versions.zio
    val test           = org.zio %% "zio-test"          % versions.zio
    val testSbt        = org.zio %% "zio-test-sbt"      % versions.zio
    val testMagnolia   = org.zio %% "zio-test-magnolia" % versions.zio
    val testFrameworks = Seq(new TestFramework("zio.test.sbt.ZTestFramework"))

    // only for examples
    val cli = org.zio %% "zio-cli" % "0.7.2"
  }

  object Enumeratum {
    val enumeratum = org.beachape %% "enumeratum" % versions.enumeratum
  }

  object Scalapb {
    val runtime         = "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion
    val runtimeProtobuf = runtime                 % "protobuf"
  }

  object Utility {
    val scalaJava8Compat  = "org.scala-lang.modules" %% "scala-java8-compat"      % "1.0.2"
    val collectionsCompat = "org.scala-lang.modules" %% "scala-collection-compat" % "2.13.0"
  }

  object ScalaReflect {
    val macros = Def.setting {
      "org.scala-lang" % "scala-reflect" % scalaVersion.value % Provided
    }
  }

  object Logging {
    val zio      = org.zio         %% "zio-logging"       % versions.zioLogging
    val zioSlf4j = org.zio         %% "zio-logging-slf4j" % versions.zioLogging
    val logback  = "ch.qos.logback" % "logback-classic"   % "1.5.18"
  }

  object Testing {
    val scalatest = "org.scalatest" %% "scalatest" % "3.2.19"
  }

  object Monitoring {
    val otelApi              = "io.opentelemetry"         % "opentelemetry-api"                         % versions.otel
    val otelExporterOtlp     = "io.opentelemetry"         % "opentelemetry-exporter-otlp"               % versions.otel
    val otelTracePropagators = "io.opentelemetry"         % "opentelemetry-extension-trace-propagators" % versions.otel
    val otelOpentracingShim  = "io.opentelemetry"         % "opentelemetry-opentracing-shim"            % versions.otel
    val otelSemvonc          = "io.opentelemetry.semconv" % "opentelemetry-semconv"                     % "1.34.0"

    val otel = Seq(otelApi, otelExporterOtlp, otelTracePropagators, otelOpentracingShim, otelSemvonc)

    val micrometerOtlp = "io.micrometer" % "micrometer-registry-otlp" % "1.15.2"
  }
}
