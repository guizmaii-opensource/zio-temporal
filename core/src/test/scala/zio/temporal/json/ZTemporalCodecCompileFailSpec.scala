package zio.temporal.json

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.compiletime.testing.*

/** Pins the compile-time errors that zio-temporal's codec gate is supposed to fire. If these tests fail, it means a
  * change has quietly broken the "no codec = no compile" guarantee.
  */
class ZTemporalCodecCompileFailSpec extends AnyWordSpec with Matchers {

  "The ZTemporalCodec implicit search" should {

    "fail to summon a codec for a user type with no JsonEncoder/JsonDecoder in scope" in {
      val errors: List[Error] = typeCheckErrors(
        """
          import zio.temporal.json.ZTemporalCodec

          final case class NoCodecType(x: Int)

          ZTemporalCodec[NoCodecType]
        """
      )
      errors should not be empty
      val messages = errors.map(_.message).mkString("\n")
      messages should include("No ZTemporalCodec")
      messages should include("NoCodecType")
    }

    "succeed when the user provides JsonEncoder + JsonDecoder on the companion" in {
      val errors: List[Error] = typeCheckErrors(
        """
          import zio.json.{DeriveJsonDecoder, DeriveJsonEncoder, JsonDecoder, JsonEncoder}
          import zio.temporal.json.ZTemporalCodec

          final case class HasCodecType(x: Int)
          object HasCodecType {
            given JsonEncoder[HasCodecType] = DeriveJsonEncoder.gen[HasCodecType]
            given JsonDecoder[HasCodecType] = DeriveJsonDecoder.gen[HasCodecType]
          }

          ZTemporalCodec[HasCodecType]
        """
      )
      errors shouldBe empty
    }
  }

  "CodecRegistry#addInterface" should {

    "fail to compile when the interface references a type without a codec" in {
      val errors: List[Error] = typeCheckErrors(
        """
          import zio.temporal.*
          import zio.temporal.json.CodecRegistry

          final case class NoCodecInput(x: Int)

          @workflowInterface
          trait NoCodecWorkflow {
            @workflowMethod
            def run(in: NoCodecInput): Unit
          }

          new CodecRegistry().addInterface[NoCodecWorkflow]
        """
      )
      errors should not be empty
      val messages = errors.map(_.message).mkString("\n")
      messages should include("Cannot auto-register codec")
      messages should include("NoCodecInput")
    }

    "fail to compile when pointed at a non-interface (or interface with no boundary methods)" in {
      val errors: List[Error] = typeCheckErrors(
        """
          import zio.temporal.json.CodecRegistry

          trait NotAnInterface {
            def plainMethod(): Unit = ()
          }

          new CodecRegistry().addInterface[NotAnInterface]
        """
      )
      errors should not be empty
      val messages = errors.map(_.message).mkString("\n")
      messages should include("has nothing to register")
    }
  }
}
