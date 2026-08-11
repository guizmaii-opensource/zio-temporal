# zio-json serialization

<head>
  <meta charset="UTF-8" />
  <meta name="description" content="ZIO Temporal zio-json serialization" />
  <meta name="keywords" content="ZIO Temporal zio-json serialization, Scala Temporal zio-json" />
</head>

zio-temporal uses [zio-json](https://github.com/zio/zio-json) as its default serialization mechanism. Unlike the
previous Jackson-based integration, this one **fails at compile time** when a workflow or activity method uses a
type that has no codec — no more silently-malformed payloads when a Scala module was not registered.

## The recommended idiom

Derive a `JsonCodec` on the domain type, then register the workflow/activity interface into a `CodecRegistry` and
hand the registry to the client:

```scala mdoc:silent
import zio.json.JsonCodec
import zio.temporal._
import zio.temporal.json.CodecRegistry
import zio.temporal.workflow.ZWorkflowClientOptions

final case class PaymentRequest(customerId: String, amount: BigDecimal, currency: String) derives JsonCodec

sealed trait PaymentStatus derives JsonCodec
object PaymentStatus {
  case object Pending                    extends PaymentStatus
  case object Completed                  extends PaymentStatus
  case class Failed(reason: String)      extends PaymentStatus
}

@workflowInterface
trait PaymentWorkflow {
  @workflowMethod
  def pay(req: PaymentRequest): PaymentStatus
}

val clientOptions =
  ZWorkflowClientOptions.make @@
    ZWorkflowClientOptions.withCodecRegistry(
      new CodecRegistry().addInterface[PaymentWorkflow]
    )
```

That's everything. `derives JsonCodec` produces a `given JsonCodec[T]` on the companion; zio-temporal ships a pair
of bridges that let zio-json's generic combinators (list/option/either/…) see the encoder and decoder underneath;
and `CodecRegistry#addInterface[I]` is a compile-time macro that walks `I`'s `@workflowMethod` / `@signalMethod` /
`@queryMethod` / `@activityMethod` methods, summons a `ZTemporalCodec` for every parameter and return type, and
emits runtime `register(...)` calls into the registry.

## What compile-time errors look like

If you use a type that has no codec anywhere a Temporal boundary is crossed:

```text
No ZTemporalCodec[com.example.MyType] in scope — Temporal needs a zio-json codec to (de)serialize
com.example.MyType across workflow/activity/signal/query boundaries.

Provide one, e.g. on com.example.MyType's companion object:

    final case class MyType(...) derives JsonCodec
```

If you register an interface that references an uncoded type:

```text
Cannot auto-register codec for type `com.example.MyType` referenced in interface `com.example.MyWorkflow`.
Reason: ...
Provide an implicit `ZTemporalCodec` for this type (typically via zio-json `JsonEncoder` + `JsonDecoder` on its
companion), then re-try `addInterface`.
```

## Alternatives

### Multiple interfaces

`addInterface` returns the registry, so chain calls:

```scala mdoc:silent
val registry = new CodecRegistry()
  .addInterface[PaymentWorkflow]
  .addInterface[PaymentActivity]
  .addInterface[NotificationWorkflow]
```

### Registering raw codecs

For ad-hoc types — e.g. a `List[MyType]` that no interface directly exposes — register a `ZTemporalCodec[T]`
explicitly:

```scala mdoc:silent
import zio.temporal.json.ZTemporalCodec

val registry2 = new CodecRegistry()
  .register(ZTemporalCodec[List[PaymentRequest]])
  .register(ZTemporalCodec[Map[String, PaymentStatus]])
```

Or, still as a single builder on `ZWorkflowClientOptions`:

```scala
ZWorkflowClientOptions.make @@
  ZWorkflowClientOptions.withCodecs(
    ZTemporalCodec[PaymentRequest],
    ZTemporalCodec[List[PaymentRequest]]
  )
```

### Separate `JsonEncoder` / `JsonDecoder`

`derives JsonCodec` is the shortest path. If you prefer explicit control — e.g. you need different encoders
depending on context — define them separately:

```scala mdoc:silent
import zio.json.{DeriveJsonDecoder, DeriveJsonEncoder, JsonDecoder, JsonEncoder}

final case class Customer(id: String, name: String)
object Customer {
  given JsonEncoder[Customer] = DeriveJsonEncoder.gen[Customer]
  given JsonDecoder[Customer] = DeriveJsonDecoder.gen[Customer]
}
```

## Unit

`Unit` is special-cased — zio-temporal ships a `ZTemporalCodec[Unit]` out of the box that serializes as an empty
JSON object `{}` and decodes any JSON to `()`. This mirrors what the previous Jackson-based integration did via its
`BoxedUnitModule`.

## What changed from the Jackson-based integration

- Jackson (`jackson-module-scala`, `jackson-datatype-jsr310`, `JacksonDataConverter`) is gone. The wire format is
  now zio-json's default shape (`{"Banana":{"curvature":0.5}}` for sealed-trait discriminators, not Jackson's
  `{"type":"Banana","curvature":0.5}`).
- Every type that crosses a Temporal boundary must have a `ZTemporalCodec[T]` in scope — compile-time error
  otherwise. Add `derives JsonCodec` on the type and it's satisfied.
- `CodecRegistry#addInterface[I]` populates the runtime registry automatically from the workflow / activity
  interface definition. No more "I forgot to register my type" runtime surprises.
- `JavaTypeTag[T]` has been fused into `ZTemporalCodec[T]`. Everywhere a `JavaTypeTag[R]` was previously required
  (`ZWorkflowStub.execute[R]`, `ZWorkflow.sideEffect`, `ApplicationFailure.getDetailsAs[T]`, etc.), a
  `ZTemporalCodec[R]` is now required instead.
