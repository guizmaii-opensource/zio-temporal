# Overview

<head>
  <meta charset="UTF-8" />
  <meta name="description" content="ZIO Temporal serialization" />
  <meta name="keywords" content="ZIO Temporal serialization, Scala Temporal serialization" />
</head>

Temporal applications communicate with the orchestrator — the Temporal Cluster.
The worker process (running workflows) and the client process (invoking workflows) may be different processes
running on different machines. While invoking workflows & activities may look like simple method invocation, it is
a remote procedure call over the network. Therefore zio-temporal (and, under the hood, the Java SDK) serializes
workflow & activity method parameters before sending them across the network.

ZIO-Temporal provides two ways to serialize data:

- As JSON objects using the [zio-json](https://github.com/zio/zio-json) library (default).
- In Protobuf binary format using the [ScalaPB](https://scalapb.github.io/) library (opt-in, via the
  `zio-temporal-protobuf` module).

The zio-json integration enforces serialization **at compile time**: a workflow or activity that returns or accepts a
type without a `ZTemporalCodec[T]` in scope will not compile, with a precise error message pointing to the type that
needs a codec. This eliminates a whole class of runtime bugs that the previous Jackson-based converter was prone to —
most notably, types that silently serialized as empty JSON because a Scala module was not registered.

See [zio-json serialization](./zio-json.md) for the typical setup and `ZTemporalCodec` derivation.

See [Protobuf serialization](./protobuf.md) for the protobuf-flavoured setup.
