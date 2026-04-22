package zio.temporal.json

import com.google.protobuf.ByteString
import io.temporal.api.common.v1.Payload
import io.temporal.common.converter.DataConverterException
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets

/** Regression test for the `Workflow.retry` CI failure: the Java SDK serializes
  * `WorkflowRetryerInternal$SerializableRetryOptions` through `mutableSideEffect`, so our chain has to handle it
  * without Jackson. See [[TemporalInternalsPayloadConverter]].
  */
class TemporalInternalsPayloadConverterSpec extends AnyWordSpec with Matchers {

  private val serializableRetryOptionsClass: Class[_] =
    Class.forName("io.temporal.internal.sync.WorkflowRetryerInternal$SerializableRetryOptions")

  private def newSerializableRetryOptions(
    initialIntervalMillis: Long,
    backoffCoefficient:    Double,
    maximumAttempts:       Int,
    maximumIntervalMillis: Long,
    doNotRetry:            Array[String] | Null
  ): Any = {
    val ctor = serializableRetryOptionsClass.getDeclaredConstructor(
      java.lang.Long.TYPE,
      java.lang.Double.TYPE,
      java.lang.Integer.TYPE,
      java.lang.Long.TYPE,
      classOf[Array[String]]
    )
    ctor.setAccessible(true)
    ctor.newInstance(
      java.lang.Long.valueOf(initialIntervalMillis),
      java.lang.Double.valueOf(backoffCoefficient),
      java.lang.Integer.valueOf(maximumAttempts),
      java.lang.Long.valueOf(maximumIntervalMillis),
      doNotRetry
    )
  }

  private def readField(instance: Any, name: String): Any = {
    val field = serializableRetryOptionsClass.getDeclaredField(name)
    field.setAccessible(true)
    field.get(instance)
  }

  "TemporalInternalsPayloadConverter" should {

    "claim SerializableRetryOptions on encode and emit json/temporal-internal metadata" in {
      val converter = new TemporalInternalsPayloadConverter()
      val value     = newSerializableRetryOptions(1000L, 2.0, 5, 60000L, Array("Foo", "Bar"))
      val payload   = converter.toData(value).orElseThrow(() => new AssertionError("expected non-empty"))

      payload.getMetadataOrThrow("encoding").toStringUtf8 shouldEqual "json/temporal-internal"
      val body = payload.getData.toStringUtf8
      body should include(""""initialIntervalMillis":1000""")
      body should include(""""backoffCoefficient":2.0""")
      body should include(""""maximumAttempts":5""")
      body should include(""""maximumIntervalMillis":60000""")
      body should include(""""doNotRetry":["Foo","Bar"]""")
    }

    "round-trip SerializableRetryOptions with a populated doNotRetry array" in {
      val converter = new TemporalInternalsPayloadConverter()
      val original  = newSerializableRetryOptions(1000L, 2.0, 5, 60000L, Array("Foo", "Bar"))
      val payload   = converter.toData(original).orElseThrow(() => new AssertionError("expected non-empty"))
      val decoded   = converter.fromData(payload, serializableRetryOptionsClass, serializableRetryOptionsClass)

      decoded.getClass shouldEqual serializableRetryOptionsClass
      readField(decoded, "initialIntervalMillis") shouldEqual 1000L
      readField(decoded, "backoffCoefficient") shouldEqual 2.0
      readField(decoded, "maximumAttempts") shouldEqual 5
      readField(decoded, "maximumIntervalMillis") shouldEqual 60000L
      readField(decoded, "doNotRetry").asInstanceOf[Array[String]].toSeq shouldEqual Seq("Foo", "Bar")
    }

    "round-trip SerializableRetryOptions with an empty doNotRetry array (the zio-temporal default)" in {
      val converter = new TemporalInternalsPayloadConverter()
      val original  = newSerializableRetryOptions(0L, 0.0, 3, 0L, Array.empty[String])
      val payload   = converter.toData(original).orElseThrow(() => new AssertionError("expected non-empty"))
      val decoded   = converter.fromData(payload, serializableRetryOptionsClass, serializableRetryOptionsClass)

      readField(decoded, "maximumAttempts") shouldEqual 3
      readField(decoded, "doNotRetry").asInstanceOf[Array[String]].toSeq shouldEqual Seq.empty
    }

    "round-trip SerializableRetryOptions with a null doNotRetry array" in {
      val converter = new TemporalInternalsPayloadConverter()
      val original  = newSerializableRetryOptions(0L, 0.0, 0, 0L, null)
      val payload   = converter.toData(original).orElseThrow(() => new AssertionError("expected non-empty"))
      val decoded   = converter.fromData(payload, serializableRetryOptionsClass, serializableRetryOptionsClass)

      readField(decoded, "doNotRetry").asInstanceOf[AnyRef] shouldBe null
    }

    "return empty Optional for classes outside the allow-list" in {
      val converter = new TemporalInternalsPayloadConverter()
      converter.toData("just a string").isPresent shouldEqual false
      converter.toData(new java.util.HashMap[String, String]()).isPresent shouldEqual false
    }

    "return empty Optional for null values" in {
      val converter = new TemporalInternalsPayloadConverter()
      converter.toData(null).isPresent shouldEqual false
    }

    "fail with DataConverterException when decoding a class outside the allow-list" in {
      val converter = new TemporalInternalsPayloadConverter()
      val payload   = Payload
        .newBuilder()
        .putMetadata("encoding", ByteString.copyFromUtf8("json/temporal-internal"))
        .setData(ByteString.copyFrom("{}", StandardCharsets.UTF_8))
        .build()
      a[DataConverterException] should be thrownBy converter.fromData(payload, classOf[String], classOf[String])
    }
  }

  "ZioJsonDataConverter" should {

    "route SerializableRetryOptions through TemporalInternalsPayloadConverter end-to-end" in {
      // Exercises the full DataConverter chain — what Temporal's `mutableSideEffect` path hits.
      val dataConverter = ZioJsonDataConverter.make(new CodecRegistry())
      val value         = newSerializableRetryOptions(1000L, 2.0, 5, 60000L, Array("Foo"))

      val payloads = dataConverter
        .toPayloads(value)
        .orElseThrow(() => new AssertionError("expected non-empty"))
      val payload = payloads.getPayloads(0)
      payload.getMetadataOrThrow("encoding").toStringUtf8 shouldEqual "json/temporal-internal"

      val decoded = dataConverter.fromPayloads(
        0,
        java.util.Optional.of(payloads),
        serializableRetryOptionsClass.asInstanceOf[Class[AnyRef]],
        serializableRetryOptionsClass
      )
      readField(decoded, "initialIntervalMillis") shouldEqual 1000L
      readField(decoded, "maximumAttempts") shouldEqual 5
      readField(decoded, "doNotRetry").asInstanceOf[Array[String]].toSeq shouldEqual Seq("Foo")
    }
  }
}
