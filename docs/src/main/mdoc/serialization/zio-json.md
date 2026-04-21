# zio-json serialization

<head>
  <meta charset="UTF-8" />
  <meta name="description" content="ZIO Temporal zio-json serialization" />
  <meta name="keywords" content="ZIO Temporal zio-json serialization, Scala Temporal zio-json" />
</head>

zio-temporal uses [zio-json](https://github.com/zio/zio-json) as the default serialization mechanism. Unlike the
previous Jackson-based integration, this one **fails at compile time** when a workflow or activity method uses a
type that has no codec — no more silently-malformed payloads when a Scala module was not registered.

## The `ZTemporalCodec` typeclass

Every type that crosses a Temporal boundary (workflow/activity/signal/query argument or return type) requires a
`zio.temporal.json.ZTemporalCodec[T]` in implicit scope. The typeclass carries a zio-json `JsonEncoder[T]` and
`JsonDecoder[T]`, plus the `Class[T]` and `java.lang.reflect.Type` that the underlying Temporal Java SDK uses to
dispatch on generic return types.

A `ZTemporalCodec[T]` is summoned automatically whenever a `JsonEncoder[T]`, a `JsonDecoder[T]`, and a `ClassTag[T]`
are all in scope — which is the case for primitives and stdlib types out of the box, and for your own types as soon
as you derive their zio-json codec.

## The typical setup for your types

Add zio-json derivations on the companion object. The `ZTemporalCodec[T]` will auto-derive from them:

```scala mdoc:silent
import zio.json.{DeriveJsonDecoder, DeriveJsonEncoder, JsonDecoder, JsonEncoder}

final case class PaymentRequest(customerId: String, amount: BigDecimal, currency: String)

object PaymentRequest {
  implicit val encoder: JsonEncoder[PaymentRequest] = DeriveJsonEncoder.gen[PaymentRequest]
  implicit val decoder: JsonDecoder[PaymentRequest] = DeriveJsonDecoder.gen[PaymentRequest]
}

sealed trait PaymentStatus
object PaymentStatus {
  case object Pending                    extends PaymentStatus
  case object Completed                  extends PaymentStatus
  case class Failed(reason: String)      extends PaymentStatus

  implicit val encoder: JsonEncoder[PaymentStatus] = DeriveJsonEncoder.gen[PaymentStatus]
  implicit val decoder: JsonDecoder[PaymentStatus] = DeriveJsonDecoder.gen[PaymentStatus]
}
```

That's it — `ZTemporalCodec[PaymentRequest]`, `ZTemporalCodec[List[PaymentRequest]]`,
`ZTemporalCodec[Option[PaymentStatus]]` etc. are all now summonable.

If you prefer the `ZTemporalCodec.derived` shortcut (wraps `zio.json.JsonCodec.derived[T]` in a single line), it works
for standalone ground types — but nested generic uses (`List[T]`, `Option[T]`, …) still require the `JsonEncoder[T]`
and `JsonDecoder[T]` instances to be summonable separately, so prefer the two-implicit pattern above.

## The compile-time gate

When you call `ZWorkflowStub.execute`, `ZChildWorkflowStub.execute`, `ZActivityStub.execute`, or any signal/query
method, zio-temporal's macros summon `ZTemporalCodec[T]` for the return type and each parameter type. If one is
missing, you get:

```text
No ZTemporalCodec[com.example.MyType] in scope — Temporal needs a zio-json codec to (de)serialize
com.example.MyType across workflow/activity/signal/query boundaries.

Provide one, e.g. on com.example.MyType's companion object:

    object MyType {
      implicit val codec: ZTemporalCodec[MyType] = ZTemporalCodec.derived
    }
```

## Registering codecs at client/worker setup

At runtime, the zio-json `DataConverter` needs to look up the encoder/decoder for each type by runtime class and
`java.lang.reflect.Type`. You populate its registry when constructing the `ZWorkflowClientOptions`:

```scala mdoc:silent
import zio.temporal.json.{CodecRegistry, ZTemporalCodec}
import zio.temporal.workflow.ZWorkflowClientOptions

val clientOptions = ZWorkflowClientOptions.make @@
  ZWorkflowClientOptions.withCodecs(
    ZTemporalCodec[PaymentRequest],
    ZTemporalCodec[PaymentStatus],
    ZTemporalCodec[Option[PaymentRequest]],
    ZTemporalCodec[List[PaymentStatus]]
  )
```

Or construct a registry directly (useful when sharing it between a client and a worker):

```scala mdoc:silent
val registry = CodecRegistry.of(
  ZTemporalCodec[PaymentRequest],
  ZTemporalCodec[PaymentStatus]
)

val clientOptions2 = ZWorkflowClientOptions.make @@
  ZWorkflowClientOptions.withCodecRegistry(registry)
```

## Unit

`Unit` is special-cased — zio-temporal ships a `ZTemporalCodec[Unit]` out of the box that serializes as an empty JSON
object `{}` and decodes any JSON to `()`. This mirrors what the previous Jackson-based integration did via its
`BoxedUnitModule`.

## What changed from the Jackson-based integration

- Jackson (`jackson-module-scala`, `jackson-datatype-jsr310`, `JacksonDataConverter`) is removed. The wire format is
  now zio-json's default shape (`{"Banana":{"curvature":0.5}}` for sealed-trait discriminators, not Jackson's
  `{"type":"Banana","curvature":0.5}`).
- Every type that crosses a Temporal boundary must have a `ZTemporalCodec[T]` in scope — compile-time error otherwise.
- `ZWorkflowClientOptions.withCodecs(...)` is the convenience entry point for runtime registration.
- `JavaTypeTag[T]` has been fused into `ZTemporalCodec[T]`, since the codec already carries the generic type
  information. Everywhere a `JavaTypeTag[R]` was previously required (`ZWorkflowStub.execute[R]`, `ZWorkflow.sideEffect`,
  `ApplicationFailure.getDetailsAs[T]`, etc.), a `ZTemporalCodec[R]` is now required instead.
