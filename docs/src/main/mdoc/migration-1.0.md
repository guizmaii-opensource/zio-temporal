# Migrating from 0.7.x to 1.0.0

<head>
  <meta charset="UTF-8" />
  <meta name="description" content="ZIO Temporal migration guide from 0.7.x to 1.0.0" />
  <meta name="keywords" content="ZIO Temporal migration, Scala Temporal 1.0.0 upgrade" />
</head>

1.0.0 replaces the Jackson-based serialization layer with [zio-json](https://github.com/zio/zio-json) and makes
`ZTemporalCodec[T]` compile-time evidence required at every workflow/activity/signal/query boundary. This is the one
headline breaking change; everything else in this guide follows from it.

## Why this change

Under the 0.7.x Jackson integration, a type without a registered Jackson module compiled fine and only failed at
*runtime* — often as a workflow that silently hangs on its first `execute()` rather than a clear error. 1.0.0 turns
that into a compile-time gate: if a type crossing a Temporal boundary has no codec, your build doesn't compile.

## Step-by-step

### 1. Bump the dependency version

The Maven coordinates (group and artifact IDs) haven't changed — this is a version bump, not a coordinate change:

```scala
libraryDependencies += "com.guizmaii" %% "zio-temporal-core" % "1.0.0"
```

If you're coming from the original upstream `dev.vhonta` project rather than from this fork's own 0.7.x line,
you'll also need to switch the group ID to `com.guizmaii` — that rename happened independently of this migration.

### 2. Add a codec to every domain type that crosses a boundary

Any type used as a workflow/activity/signal/query parameter or return type needs a `JsonCodec` (or `ZTemporalCodec`
directly) derived on its companion:

```scala
import zio.json.JsonCodec

final case class PaymentRequest(customerId: String, amount: BigDecimal) derives JsonCodec

sealed trait PaymentStatus derives JsonCodec
object PaymentStatus {
  case object Pending                extends PaymentStatus
  case class  Failed(reason: String) extends PaymentStatus
}
```

This is almost always the actual work in a migration — everything else below is mechanical. See
[ZIO-JSON serialization](./serialization/zio-json.md) for the full picture (derivation, `@jsonField`, generic types,
`Unit`, etc.).

### 3. Replace `JavaTypeTag` usage

`JavaTypeTag[T]` is gone, fused into `ZTemporalCodec[T]`. If your code had explicit `given JavaTypeTag[X]` instances
or `JavaTypeTag[R]` context bounds (custom stub wrappers, generic helpers, etc.), replace them with
`ZTemporalCodec[R]` — same shape, same call sites (`ZWorkflowStub.execute[R]`, `ZWorkflow.sideEffect`,
`ApplicationFailure.getDetailsAs[T]`, and so on).

### 4. Register your interfaces — or don't

As of 1.0.0, calling `ZWorker.addWorkflow[I]`, `ZWorker.addActivityImplementation(...)`, or
`client.newWorkflowStub[I](...)` automatically registers that interface's codecs — see
[Auto-registration](./serialization/zio-json.md#auto-registration). For most workers and clients, step 2 is the
*only* change you need to make; no `CodecRegistry` wiring required.

Reach for `CodecRegistry#addInterface[I]` explicitly only if you want a fail-fast strict check at startup, or you're
building a `CodecRegistry` for a `DataConverter` that doesn't go through the normal client wiring (protobuf — see
step 5).

### 5. Protobuf: `ProtobufDataConverter.make` now takes a registry

```scala
// Before
ProtobufDataConverter.make()

// After
ProtobufDataConverter.make(
  new CodecRegistry().addInterface[YourWorkflow]
)
```

Since `ProtobufDataConverter` is handed to `withDataConverter`, it doesn't participate in auto-registration (that
call is exactly what opts a client out of it) — build the registry explicitly. See
[Protobuf](./serialization/protobuf.md).

### 6. Sum-type JSON shape changed

Jackson encoded a sealed trait's subtype as `{"type":"Banana","curvature":0.5}`. zio-json's default shape is
`{"Banana":{"curvature":0.5}}`. If you need the old shape back — e.g. for compatibility with an external system
reading the JSON, not for workflow history replay (see step 8) — annotate the sealed trait:

```scala
import zio.json.jsonDiscriminator

@jsonDiscriminator("type")
sealed trait PaymentStatus derives JsonCodec
```

### 7. Generic wrapper types: one instantiation per raw class

If you have a generic case class like `Triple[A, B, C]` and reach a single worker/client with two *different*
instantiations sharing the raw class (e.g. `Triple[Foo, Int, String]` and `Triple[Option[Int], Set[UUID], Boolean]`),
encoding now fails with a clear ambiguity error instead of silently picking the wrong shape — `v.getClass` alone
can't disambiguate them. Split into distinct wrapper types if you hit this. `List` / `Map` / `Either` and other
collections are unaffected, since they dispatch per-element rather than by wrapper class.

### 8. In-flight workflows: no action needed

Workflow histories already recorded under the Jackson `json/plain` encoding **replay transparently** — a decode-only
`json/plain` compatibility converter, backed by the same `CodecRegistry`, handles them. Fresh payloads are always
written under the new `json/zio` encoding; you don't need to regenerate or migrate any recorded history.

## What compile errors look like

A type used directly at a call site (`.execute`, `.query`, `.signal`, `sideEffect`, …) with no codec:

```text
No ZTemporalCodec[com.example.MyType] in scope — Temporal needs a zio-json codec to (de)serialize
com.example.MyType across workflow/activity/signal/query boundaries.

Provide one, e.g. on com.example.MyType's companion object:

    final case class MyType(...) derives JsonCodec
```

An interface referencing an uncoded type via explicit `addInterface`:

```text
Cannot auto-register codec for type `com.example.MyType` referenced in interface `com.example.MyWorkflow`.
Reason: ...
Provide an implicit `ZTemporalCodec` for this type (typically via zio-json `JsonEncoder` + `JsonDecoder` on its
companion), then re-try `addInterface`.
```

The same situation hit through auto-registration (step 4) is a compiler *warning*, not a build failure — see
[Auto-registration](./serialization/zio-json.md#auto-registration) for why, and what to do about it.

## Getting help

If you hit a case this guide doesn't cover, please [open an issue](https://github.com/guizmaii-opensource/zio-temporal/issues) —
migration gaps are exactly the kind of thing worth documenting for the next person.
