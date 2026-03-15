# Ironclad RPC

Typed request/response RPC over any message transport, built on Mutiny.

This library gives you:

- Typed unary RPC with `Uni<TResponse>`
- Typed streaming RPC with `Multi<TResponse>`
- Transport-agnostic wiring through `RcpConnection`
- Request/response correlation and response-type validation
- Unary retries with fresh request IDs per attempt
- Client and server interceptors for both unary and streaming calls

It does not provide serialization, socket management, or a specific transport. You bring the wire format and call the runtime when connections open, close, and receive decoded messages.

## Requirements

- Java 23
- Maven

## Core Model

Every wire message extends [`RcpMessage`](/home/luka/Projects/Personal/ironclad-rpc/src/main/java/bot/ironclad/protocol/RcpMessage.java). There are two request shapes:

- [`RcpRequest<TResponse>`](/home/luka/Projects/Personal/ironclad-rpc/src/main/java/bot/ironclad/protocol/RcpRequest.java) for unary calls
- [`RcpStreamRequest<TResponse>`](/home/luka/Projects/Personal/ironclad-rpc/src/main/java/bot/ironclad/protocol/RcpStreamRequest.java) for streaming calls

There are also two handler shapes:

- [`MessageHandler<TReq, TRes>`](/home/luka/Projects/Personal/ironclad-rpc/src/main/java/bot/ironclad/handler/MessageHandler.java) returns `Uni<TRes>`
- [`StreamMessageHandler<TReq, TRes>`](/home/luka/Projects/Personal/ironclad-rpc/src/main/java/bot/ironclad/handler/StreamMessageHandler.java) returns `Multi<TRes>`

That split is the protocol contract:

- unary request -> exactly one response item or an error
- stream request -> zero or more response items, then stream completion or an error

## Transport Contract

The runtime only needs a connection object that can send decoded messages:

```java
package example;

import bot.ironclad.connection.RcpConnection;
import bot.ironclad.protocol.RcpMessage;

import java.util.UUID;

final class WsConnection implements RcpConnection {
    private final UUID id;

    WsConnection(UUID id) {
        this.id = id;
    }

    UUID id() {
        return id;
    }

    @Override
    public void send(RcpMessage message) {
        // Serialize and write to your websocket, TCP socket, broker, etc.
    }
}
```

You own:

- encoding and decoding `RcpMessage` subclasses
- assigning a stable connection ID
- calling the runtime on lifecycle events

## Quick Start

### 1. Create the runtime

```java
import bot.ironclad.runtime.RcpRuntime;

var runtime = RcpRuntime.<WsConnection>builder(WsConnection::id)
        .responseTimeout(java.time.Duration.ofSeconds(30))
        .build();
```

### 2. Notify it about connection lifecycle

```java
runtime.onOpen(connection);

// After you decode an inbound message:
runtime.onMessage(connection.id(), decodedMessage);

runtime.onClose(connection);
```

### 3. Define message types

```java
package example;

import bot.ironclad.protocol.RcpMessage;
import bot.ironclad.protocol.RcpRequest;
import bot.ironclad.protocol.RcpStreamRequest;

final class PingRequest extends RcpRequest<PongResponse> {
    private final String payload;

    PingRequest(String payload) {
        super(PongResponse.class);
        this.payload = payload;
    }

    String payload() {
        return payload;
    }
}

final class PongResponse extends RcpMessage {
    private final String payload;

    PongResponse(String payload) {
        this.payload = payload;
    }

    String payload() {
        return payload;
    }
}

final class ChunkRequest extends RcpStreamRequest<ChunkResponse> {
    private final String prefix;

    ChunkRequest(String prefix) {
        super(ChunkResponse.class);
        this.prefix = prefix;
    }

    String prefix() {
        return prefix;
    }
}

final class ChunkResponse extends RcpMessage {
    private final String payload;

    ChunkResponse(String payload) {
        this.payload = payload;
    }

    String payload() {
        return payload;
    }
}
```

### 4. Register handlers

Unary handlers return `Uni<TResponse>`:

```java
import io.smallrye.mutiny.Uni;

runtime.registerHandler(
        PingRequest.class,
        request -> Uni.createFrom().item(new PongResponse("pong:" + request.payload()))
);
```

Streaming handlers return `Multi<TResponse>`:

```java
import io.smallrye.mutiny.Multi;

runtime.registerStreamHandler(
        ChunkRequest.class,
        request -> Multi.createFrom().items(
                new ChunkResponse(request.prefix() + ":1"),
                new ChunkResponse(request.prefix() + ":2")
        )
);
```

## Sending Requests

Unary request:

```java
Uni<PongResponse> response = runtime.send(connection.id(), new PingRequest("ping"));

response.subscribe().with(
        pong -> System.out.println(pong.payload()),
        failure -> failure.printStackTrace()
);
```

Streaming request:

```java
Multi<ChunkResponse> stream = runtime.send(connection.id(), new ChunkRequest("chunk"));

stream.subscribe().with(
        chunk -> System.out.println(chunk.payload()),
        failure -> failure.printStackTrace(),
        () -> System.out.println("stream complete")
);
```

There are matching convenience methods:

- `sendMessageAndGetResponse(...)`
- `sendMessageAndGetResponses(...)`

They are aliases around the same unary and streaming send paths.

## Retries

Unary retries are built in through [`RcpRetryPolicy`](/home/luka/Projects/Personal/ironclad-rpc/src/main/java/bot/ironclad/runtime/RcpRetryPolicy.java):

```java
import bot.ironclad.runtime.RcpRetryPolicy;

Uni<PongResponse> response = runtime.sendWithRetry(
        connection.id(),
        () -> new PingRequest("ping"),
        RcpRetryPolicy.timeoutOnly(3)
);
```

Important details:

- retries are unary only
- the request factory must return a fresh request instance on every attempt
- each retry gets a fresh message ID
- without receiver-side deduplication, retries are at-least-once

Available builders:

- `RcpRetryPolicy.timeoutOnly(maxAttempts)`
- `RcpRetryPolicy.noDelay(maxAttempts, predicate)`
- `RcpRetryPolicy.fixedDelay(maxAttempts, delay, predicate)`
- `RcpRetryPolicy.builder()`

## Interceptors

Client and server interceptors can wrap both unary and streaming calls:

- [`RcpOutboundInterceptor`](/home/luka/Projects/Personal/ironclad-rpc/src/main/java/bot/ironclad/interceptor/RcpOutboundInterceptor.java)
- [`RcpInboundInterceptor`](/home/luka/Projects/Personal/ironclad-rpc/src/main/java/bot/ironclad/interceptor/RcpInboundInterceptor.java)

Example client interceptor:

```java
runtime.addOutboundInterceptor(new RcpOutboundInterceptor<WsConnection>() {
    @Override
    public <TReq extends RcpRequest<TRes>, TRes extends RcpMessage> Uni<TRes> interceptUnary(
            WsConnection connection,
            java.util.UUID connectionId,
            TReq request,
            java.util.function.Function<TReq, Uni<TRes>> next
    ) {
        return next.apply(request);
    }
});
```

Use interceptors for:

- auth
- tracing
- metrics
- structured logging
- request enrichment

Interceptors run in registration order.

You can register them either after construction:

```java
runtime.addOutboundInterceptor(outboundInterceptor);
runtime.addInboundInterceptor(inboundInterceptor);
```

or directly on the builder:

```java
var runtime = RcpRuntime.<WsConnection>builder(WsConnection::id)
        .outboundInterceptor(outboundInterceptor)
        .inboundInterceptor(inboundInterceptor)
        .build();
```

## Errors

If a handler fails, the runtime serializes that failure into [`RcpErrorResponse`](/home/luka/Projects/Personal/ironclad-rpc/src/main/java/bot/ironclad/protocol/RcpErrorResponse.java). On the caller side, that becomes [`RcpRemoteException`](/home/luka/Projects/Personal/ironclad-rpc/src/main/java/bot/ironclad/runtime/RcpRemoteException.java).

The runtime also fails calls when:

- the connection is closed while a response is pending
- the response type does not match the request’s declared `responseType`
- a unary response or stream item does not arrive before the configured timeout

## Threading Model

The runtime is thread-safe enough to support concurrent use, but it is not single-threaded and it does not impose an event-loop model.

In practice:

- `send(...)` runs on the thread that subscribes to the returned `Uni` or `Multi`
- `onMessage(...)` runs on whichever thread delivers the decoded inbound message
- unary handlers and server unary interceptors execute on that inbound thread unless their `Uni` switches threads
- stream handlers emit on whatever thread their `Multi` uses

That means blocking work is your responsibility. If your transport framework uses event-loop threads, do not block them inside handlers or interceptors.

## What the Runtime Handles

- message ID generation when missing
- correlation IDs for responses
- tracking pending unary and stream responses
- stream completion via [`RcpStreamCompleted`](/home/luka/Projects/Personal/ironclad-rpc/src/main/java/bot/ironclad/protocol/RcpStreamCompleted.java)
- pending-call cleanup on connection close

## What You Still Need to Provide

- message serialization format
- class registration or polymorphic decoding strategy
- transport implementation
- auth, tracing, metrics, and logging policy
- deduplication if you need stronger semantics than at-least-once retry behavior

## Development

Run the test suite with:

```bash
mvn test
```

Current coverage includes:

- inbound unary dispatch
- inbound streaming dispatch
- async unary completion
- unary retries
- remote error propagation
- client and server interceptors
- connection-close cleanup
