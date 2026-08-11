# zio-json serialization

<head>
  <meta charset="UTF-8" />
  <meta name="description" content="ZIO Temporal zio-json serialization" />
  <meta name="keywords" content="ZIO Temporal zio-json serialization, Scala Temporal zio-json" />
</head>

zio-temporal uses [zio-json](https://github.com/zio/zio-json) as its default serialization mechanism. Unlike the
previous Jackson-based integration, every type that crosses a workflow/activity/signal/query boundary must have a
codec — a missing one is either a compile-time error or, at worst, a warning at the exact call site that's missing
it (see [Auto-registration](#auto-registration) below) — never a silently-malformed payload from an unregistered
Scala module.

## The recommended idiom

Derive a `JsonCodec` on every domain type that crosses a boundary. That's it — nothing else to wire up:

```scala mdoc:silent
import zio.json.JsonCodec
import zio.temporal._

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
```

`derives JsonCodec` produces a `given JsonCodec[T]` on the companion; zio-temporal ships a pair of bridges that let
zio-json's generic combinators (list/option/either/…) see the encoder and decoder underneath. No `CodecRegistry`
construction is needed for this example to work end-to-end — the codecs for `PaymentWorkflow`'s boundary types are
registered automatically the moment the interface is actually used, at the worker and client call sites described
next.

## Auto-registration

Every place zio-temporal already needs to know about a workflow/activity interface — because you're registering it
on a worker or creating a stub for it — also registers that interface's codecs into the client's `CodecRegistry`,
with no separate step:

```scala
// Worker side
ZWorker.addWorkflow[PaymentWorkflowImpl].fromClass       // registers PaymentWorkflow's codecs
ZWorker.addActivityImplementation(new PaymentActivityImpl) // registers PaymentActivity's codecs

// Client side
client.newWorkflowStub[PaymentWorkflow](options)          // registers PaymentWorkflow's codecs
```

This is what makes the idiom above work with zero `CodecRegistry` wiring: `ZWorkflowClientOptions.make` already
carries a (initially empty) registry when the default zio-json `DataConverter` is in use, and each call above
mutates it in place. `CodecRegistry#addInterface[I]` (below) still exists and does the same walk, but calling it
explicitly is now the exception rather than the rule.

Auto-registration is **non-strict**: unlike `addInterface`, a referenced type with no summonable codec doesn't fail
the build — it emits a compiler *warning* and is skipped. This matters for types that are deliberately uncodec-able
(e.g. a Scala 3 union type like `Int | Null`, which erases to `Object` and is handled by a runtime fallback instead
of a registered codec) — those shouldn't block compilation just because a workflow using them was added to a
worker. If the skipped type actually gets serialized, it still fails clearly at runtime with `No ZTemporalCodec
registered for …`, same as before this feature existed — auto-registration only removes boilerplate, it never
weakens the guarantee you'd get from writing `addInterface` yourself for a type that does need a codec.

A registry-backed client opts out entirely with `withDataConverter(raw)`: that clears the tracked registry, so the
`foreach`-guarded auto-registration calls above become no-ops (see [Registering raw codecs](#registering-raw-codecs)
below for the pattern this implies when using a non-default `DataConverter`, e.g. `ProtobufDataConverter`).

## What compile-time errors look like

If you use a type that has no codec anywhere a Temporal boundary is crossed:

```text
No ZTemporalCodec[com.example.MyType] in scope — Temporal needs a zio-json codec to (de)serialize
com.example.MyType across workflow/activity/signal/query boundaries.

Provide one, e.g. on com.example.MyType's companion object:

    final case class MyType(...) derives JsonCodec
```

If you explicitly call `addInterface` on an interface that references an uncoded type, compilation fails the same
way:

```text
Cannot auto-register codec for type `com.example.MyType` referenced in interface `com.example.MyWorkflow`.
Reason: ...
Provide an implicit `ZTemporalCodec` for this type (typically via zio-json `JsonEncoder` + `JsonDecoder` on its
companion), then re-try `addInterface`.
```

If instead the same missing codec is hit through auto-registration (`ZWorker.addWorkflow[...]`, `newWorkflowStub[...]`,
etc.), you get a warning at that call site instead of a hard failure — same message, different severity:

```text
[warn] Cannot auto-register codec for type `com.example.MyType` referenced in interface `com.example.MyWorkflow` —
[warn] skipping (auto-registration is non-strict).
[warn] Reason: ...
[warn] If this type is actually serialized, this will fail at runtime with `No ZTemporalCodec registered for …`.
[warn] Provide an implicit `ZTemporalCodec` for this type, or if this is intentional (e.g. an erased union type
[warn] with a runtime fallback), ignore this warning.
```

## Explicit registration (`addInterface`)

Auto-registration covers the common case, but calling `CodecRegistry#addInterface[I]` yourself is still useful
when you want:

- **A fail-fast, strict check** at a single call site instead of scattered warnings at every `addWorkflow`/
  `newWorkflowStub` call — useful right after building `ZWorkflowClientOptions`, so a missing codec is caught at
  startup rather than the first time that particular workflow is touched.
- **A registry populated before construction**, for `DataConverter`s that don't participate in auto-registration
  at all — e.g. `ProtobufDataConverter.make(registry)` (see [Protobuf](./protobuf.md)) needs its registry built
  upfront, since it's handed to `withDataConverter`, which is exactly the call that opts a client out of
  auto-registration.

```scala mdoc:silent
import zio.temporal.workflow.ZWorkflowClientOptions

val clientOptions =
  ZWorkflowClientOptions.make @@
    ZWorkflowClientOptions.withCodecRegistry(
      new CodecRegistry().addInterface[PaymentWorkflow]
    )
```

### Multiple interfaces

`addInterface` returns the registry, so chain calls — one per workflow/activity interface your worker or client uses:

```scala
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
- [Auto-registration](#auto-registration) goes a step further: `ZWorker.addWorkflow`, `ZWorker.addActivityImplementation`,
  and `newWorkflowStub` now call `addInterface`'s underlying macro for you, so the common case needs no
  `CodecRegistry` wiring at all — not even the single `addInterface` call the initial zio-json migration required.
- `JavaTypeTag[T]` has been fused into `ZTemporalCodec[T]`. Everywhere a `JavaTypeTag[R]` was previously required
  (`ZWorkflowStub.execute[R]`, `ZWorkflow.sideEffect`, `ApplicationFailure.getDetailsAs[T]`, etc.), a
  `ZTemporalCodec[R]` is now required instead.
