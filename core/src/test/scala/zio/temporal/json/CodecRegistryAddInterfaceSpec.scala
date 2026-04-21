package zio.temporal.json

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import zio.json.JsonCodec
import zio.temporal.*

class CodecRegistryAddInterfaceSpec extends AnyWordSpec with Matchers {

  import CodecRegistryAddInterfaceSpec.*

  "CodecRegistry.addInterface" should {

    "register codecs for every parameter and return type of a workflow interface" in {
      val r = new CodecRegistry().addInterface[MyWorkflow]
      r.encoderForClass(classOf[User]) should not be null
      r.decoderForType(classOf[User]) should not be null
      r.decoderForType(ZTemporalCodec[List[User]].genericType) should not be null
      r.encoderForClass(classOf[String]) should not be null
    }

    "register codecs for signal and query methods as well" in {
      val r = new CodecRegistry().addInterface[MyWorkflow]
      // Signal method `updateName(String): Unit` — String + Unit codecs
      r.decoderForType(classOf[String]) should not be null
      // Query method `getSummary(): String` — String return
      r.decoderForType(classOf[String]) should not be null
    }

    "register codecs for an activity interface" in {
      val r = new CodecRegistry().addInterface[MyActivity]
      r.encoderForClass(classOf[User]) should not be null
      r.decoderForType(classOf[Int]) should not be null
    }

    "chain fluently across multiple interfaces" in {
      val r = new CodecRegistry()
        .addInterface[MyWorkflow]
        .addInterface[MyActivity]
      // Types from both interfaces are reachable.
      r.encoderForClass(classOf[User]) should not be null
      r.decoderForType(classOf[Int]) should not be null
    }

    "register codecs for inherited methods when a workflow interface extends a parameterized parent" in {
      // SpecificWorkflow inherits `process(input: User)` from Parent[User]
      val r = new CodecRegistry().addInterface[SpecificWorkflow]
      r.encoderForClass(classOf[User]) should not be null
    }

    "promote a sealed-trait subtype to its sealed parent when walking a parameterized interface" in {
      // `DrinkSodaWorkflow extends DrinkWorkflow[Drink.Soda]`. Without promotion the macro would register a codec
      // keyed on the concrete `Drink.Soda` class (its flat zio-json shape `{"kind":"x"}`) and the decode side —
      // which on the inherited method resolves the TypeVariable to the upper bound `Drink` — would expect the
      // wrapped shape `{"Soda":{"kind":"x"}}`. The macro must instead register only the parent codec.
      val r = new CodecRegistry().addInterface[DrinkSodaWorkflow]

      val classNames = r.registeredClassNames.toSet
      classNames should contain(classOf[Drink].getName)
      classNames should not contain classOf[Drink.Soda].getName
      classNames should not contain classOf[Drink.Juice].getName

      r.encoderForClass(classOf[Drink.Soda]) should not be null
      r.decoderForType(classOf[Drink]) should not be null
    }
  }
}

object CodecRegistryAddInterfaceSpec {
  final case class User(id: Int, name: String) derives JsonCodec

  @workflowInterface
  trait MyWorkflow {
    @workflowMethod
    def run(user: User, extra: String): List[User]

    @signalMethod
    def updateName(newName: String): Unit

    @queryMethod
    def getSummary(): String
  }

  @activityInterface
  trait MyActivity {
    @activityMethod
    def save(user: User): Int
  }

  trait Parent[A] {
    @workflowMethod
    def process(input: A): Unit
  }

  @workflowInterface
  trait SpecificWorkflow extends Parent[User]

  sealed trait Drink derives JsonCodec
  object Drink {
    final case class Soda(kind: String)  extends Drink derives JsonCodec
    final case class Juice(kind: String) extends Drink derives JsonCodec
  }

  trait DrinkWorkflow[Input <: Drink] {
    @workflowMethod
    def serve(input: Input): Unit
  }

  @workflowInterface
  trait DrinkSodaWorkflow extends DrinkWorkflow[Drink.Soda]
}
