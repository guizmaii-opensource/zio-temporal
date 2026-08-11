package zio.temporal.json

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import zio.json.JsonCodec
import zio.temporal.*

/** Unit tests for `CodecRegistry.autoRegisterInterface[I]` and `CodecRegistry.autoRegisterActivityImpl[A]`.
  *
  * These exercise the same macro machinery as `addInterface[I]` but through the auto-registration entry points that the
  * inline methods on `ZWorker` / `ZWorkflowClient` invoke. Expected behaviours:
  *
  *   1. When the registry is `Some(r)`, codecs for `I`'s boundary methods are registered into `r`.
  *   2. When the registry is `None`, the call is a silent no-op — no side effects, no compile error.
  *   3. Repeated calls are idempotent (registry ends up the same).
  *   4. The macro walks supertype chains for `@workflowInterface` / `@activityInterface` ancestors so the user can pass
  *      an implementation class as the type parameter.
  */
class CodecRegistryAutoRegisterSpec extends AnyWordSpec with Matchers {

  import CodecRegistryAutoRegisterSpec.*

  "CodecRegistry.autoRegisterInterface" should {

    "populate Some(registry) with every boundary-method parameter and return type" in {
      val r = new CodecRegistry()
      CodecRegistry.autoRegisterInterface[MyAutoWorkflow](Some(r))
      r.encoderForClass(classOf[AutoUser]) should not be null
      r.decoderForType(classOf[AutoUser]) should not be null
      r.decoderForType(classOf[String]) should not be null
    }

    "be a silent no-op when registry is None" in {
      noException should be thrownBy {
        CodecRegistry.autoRegisterInterface[MyAutoWorkflow](None)
      }
      // Build a separate registry to confirm None doesn't magically populate one.
      val r = new CodecRegistry()
      r.size shouldBe 0
    }

    "be idempotent when called multiple times with the same interface" in {
      val r = new CodecRegistry()
      CodecRegistry.autoRegisterInterface[MyAutoWorkflow](Some(r))
      val sizeAfterFirst = r.size
      CodecRegistry.autoRegisterInterface[MyAutoWorkflow](Some(r))
      CodecRegistry.autoRegisterInterface[MyAutoWorkflow](Some(r))
      r.size shouldBe sizeAfterFirst
    }

    "walk to @workflowInterface ancestor when given an implementation class" in {
      val r = new CodecRegistry()
      CodecRegistry.autoRegisterInterface[MyAutoWorkflowImpl](Some(r))
      // The impl has no @workflowMethod directly — codecs come from walking to MyAutoWorkflow.
      r.encoderForClass(classOf[AutoUser]) should not be null
      r.decoderForType(classOf[AutoUser]) should not be null
    }

    "silently skip types without a summonable codec (non-strict mode)" in {
      val r = new CodecRegistry()
      // WorkflowWithUncodecableType's method takes `Any` which has no ZTemporalCodec. Non-strict mode
      // must skip it rather than abort compilation.
      CodecRegistry.autoRegisterInterface[WorkflowWithUncodecableType](Some(r))
      // The method also takes `String` which does have a codec, so that should still be registered.
      r.decoderForType(classOf[String]) should not be null
    }
  }

  "CodecRegistry.autoRegisterActivityImpl" should {

    "register codecs for the @activityInterface ancestor(s) of the impl class" in {
      val r = new CodecRegistry()
      CodecRegistry.autoRegisterActivityImpl[MyAutoActivityImpl](Some(r))
      r.encoderForClass(classOf[AutoUser]) should not be null
      r.decoderForType(classOf[Int]) should not be null
    }

    "be a silent no-op when registry is None" in {
      noException should be thrownBy {
        CodecRegistry.autoRegisterActivityImpl[MyAutoActivityImpl](None)
      }
    }

    "handle an impl that implements multiple @activityInterface types" in {
      val r = new CodecRegistry()
      CodecRegistry.autoRegisterActivityImpl[MultiActivityImpl](Some(r))
      // Both interfaces' referenced types should be registered.
      r.decoderForType(classOf[AutoUser]) should not be null
      r.decoderForType(classOf[AutoOrder]) should not be null
    }
  }
}

object CodecRegistryAutoRegisterSpec {
  final case class AutoUser(id: Int, name: String) derives JsonCodec
  final case class AutoOrder(sku: String, quantity: Int) derives JsonCodec

  @workflowInterface
  trait MyAutoWorkflow {
    @workflowMethod
    def run(user: AutoUser): String
  }

  // An implementation class — the macro should walk to its MyAutoWorkflow ancestor.
  class MyAutoWorkflowImpl extends MyAutoWorkflow {
    override def run(user: AutoUser): String = user.name
  }

  // Has a method with an uncodec-able type parameter (Any).
  @workflowInterface
  trait WorkflowWithUncodecableType {
    @workflowMethod
    def process(value: Any, label: String): String
  }

  @activityInterface
  trait MyAutoActivity {
    def save(user: AutoUser): Int
  }

  class MyAutoActivityImpl extends MyAutoActivity {
    override def save(user: AutoUser): Int = user.id
  }

  @activityInterface
  trait OrderActivity {
    def place(order: AutoOrder): String
  }

  class MultiActivityImpl extends MyAutoActivity with OrderActivity {
    override def save(user:   AutoUser): Int     = user.id
    override def place(order: AutoOrder): String = order.sku
  }
}
