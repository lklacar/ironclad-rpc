package bot.ironclad.runtime;

import bot.ironclad.connection.RcpConnection;
import bot.ironclad.interceptor.RcpInboundInterceptor;
import bot.ironclad.interceptor.RcpOutboundInterceptor;
import bot.ironclad.protocol.RcpErrorResponse;
import bot.ironclad.protocol.RcpMessage;
import bot.ironclad.protocol.RcpRequest;
import bot.ironclad.protocol.RcpStreamCompleted;
import bot.ironclad.protocol.RcpStreamRequest;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RcpRuntimeTest {
    private TestConnection connection;
    private RcpRuntime<TestConnection> runtime;

    @BeforeEach
    void setUp() {
        connection = new TestConnection(UUID.randomUUID());
        runtime = RcpRuntime.<TestConnection>builder(TestConnection::id)
                .responseTimeout(Duration.ofSeconds(1))
                .build();
        runtime.onOpen(connection);
    }

    @Test
    void dispatchesIncomingRequestsToTheirTypedHandler() {
        runtime.registerHandler(PingRequest.class, request -> Uni.createFrom().item(new PongResponse("pong:" + request.payload())));

        var request = new PingRequest("ping");
        request.setId(UUID.randomUUID());

        runtime.onMessage(connection.id(), request);

        var response = connection.awaitMessage(0, PongResponse.class);
        assertNotNull(response.getId());
        assertEquals(request.getId(), response.getCorrelationId());
        assertEquals("pong:ping", response.payload());
    }

    @Test
    void dispatchesIncomingRequestsWhenUnaryHandlerCompletesLater() {
        runtime.registerHandler(
                PingRequest.class,
                request -> Uni.createFrom()
                        .item(new PongResponse("later:" + request.payload()))
                        .onItem()
                        .delayIt()
                        .by(Duration.ofMillis(20))
        );

        var request = new PingRequest("ping");
        request.setId(UUID.randomUUID());

        runtime.onMessage(connection.id(), request);

        var response = connection.awaitMessage(0, PongResponse.class);
        assertEquals("later:ping", response.payload());
        assertEquals(request.getId(), response.getCorrelationId());
    }

    @Test
    void dispatchesIncomingStreamRequestsToTheirTypedHandler() {
        runtime.registerStreamHandler(
                ChunkRequest.class,
                request -> Multi.createFrom().items(
                        new ChunkResponse(request.prefix() + ":1"),
                        new ChunkResponse(request.prefix() + ":2")
                )
        );

        var request = new ChunkRequest("chunk");
        request.setId(UUID.randomUUID());

        runtime.onMessage(connection.id(), request);

        assertEquals(3, connection.sentMessages().size());

        var first = connection.message(0, ChunkResponse.class);
        assertNotNull(first.getId());
        assertEquals(request.getId(), first.getCorrelationId());
        assertEquals("chunk:1", first.payload());

        var second = connection.message(1, ChunkResponse.class);
        assertNotNull(second.getId());
        assertEquals(request.getId(), second.getCorrelationId());
        assertEquals("chunk:2", second.payload());

        var completed = connection.message(2, RcpStreamCompleted.class);
        assertNotNull(completed.getId());
        assertEquals(request.getId(), completed.getCorrelationId());
    }

    @Test
    void resolvesOutboundResponsesUsingTheRequestResponseContract() {
        var request = new PingRequest("ping");

        var responseStage = runtime.send(connection, request).subscribe().asCompletionStage();
        var outboundRequest = connection.singleMessage(PingRequest.class);

        var response = new PongResponse("pong");
        response.setId(UUID.randomUUID());
        response.setCorrelationId(outboundRequest.getId());

        runtime.onMessage(connection.id(), response);

        assertSame(response, responseStage.toCompletableFuture().join());
    }

    @Test
    void outboundInterceptorsWrapUnaryCallsInRegistrationOrder() {
        var events = new ArrayList<String>();
        runtime.addOutboundInterceptor(new RecordingOutboundInterceptor(events, "first"));
        runtime.addOutboundInterceptor(new RecordingOutboundInterceptor(events, "second"));

        var responseStage = runtime.send(connection, new PingRequest("ping")).subscribe().asCompletionStage();
        var outboundRequest = connection.singleMessage(PingRequest.class);

        var response = new PongResponse("pong");
        response.setId(UUID.randomUUID());
        response.setCorrelationId(outboundRequest.getId());
        runtime.onMessage(connection.id(), response);

        var finalResponse = responseStage.toCompletableFuture().join();
        assertEquals("pong|second|first", finalResponse.payload());
        assertEquals("ping|first|second", outboundRequest.payload());
        assertEquals(
                List.of(
                        "first:before:ping",
                        "second:before:ping|first",
                        "second:after:pong",
                        "first:after:pong|second"
                ),
                events
        );
    }

    @Test
    void resolvesOutboundStreamResponsesUsingTheRequestResponseContract() {
        var probe = StreamProbe.capture(runtime.send(connection, new ChunkRequest("chunk")));
        var outboundRequest = connection.singleMessage(ChunkRequest.class);

        var first = new ChunkResponse("chunk:1");
        first.setId(UUID.randomUUID());
        first.setCorrelationId(outboundRequest.getId());
        runtime.onMessage(connection.id(), first);

        var second = new ChunkResponse("chunk:2");
        second.setId(UUID.randomUUID());
        second.setCorrelationId(outboundRequest.getId());
        runtime.onMessage(connection.id(), second);

        var completed = new RcpStreamCompleted();
        completed.setId(UUID.randomUUID());
        completed.setCorrelationId(outboundRequest.getId());
        runtime.onMessage(connection.id(), completed);

        probe.awaitCompletion();
        assertEquals(List.of("chunk:1", "chunk:2"), probe.payloads(ChunkResponse::payload));
    }

    @Test
    void outboundInterceptorsWrapStreamCalls() {
        runtime.addOutboundInterceptor(new RcpOutboundInterceptor<>() {
            @Override
            @SuppressWarnings("unchecked")
            public <TReq extends RcpStreamRequest<TRes>, TRes extends RcpMessage> Multi<TRes> interceptStream(
                    TestConnection interceptedConnection,
                    UUID connectionId,
                    TReq request,
                    Function<TReq, Multi<TRes>> next
            ) {
                return next.apply((TReq) new ChunkRequest("client:" + ((ChunkRequest) request).prefix()))
                        .onItem()
                        .transform(item -> (TRes) new ChunkResponse(((ChunkResponse) item).payload() + ":client"));
            }
        });

        var probe = StreamProbe.capture(runtime.send(connection, new ChunkRequest("chunk")));
        var outboundRequest = connection.singleMessage(ChunkRequest.class);

        var first = new ChunkResponse("one");
        first.setId(UUID.randomUUID());
        first.setCorrelationId(outboundRequest.getId());
        runtime.onMessage(connection.id(), first);

        var completed = new RcpStreamCompleted();
        completed.setId(UUID.randomUUID());
        completed.setCorrelationId(outboundRequest.getId());
        runtime.onMessage(connection.id(), completed);

        probe.awaitCompletion();
        assertEquals("client:chunk", outboundRequest.prefix());
        assertEquals(List.of("one:client"), probe.payloads(ChunkResponse::payload));
    }

    @Test
    void rejectsResponsesThatDoNotMatchTheDeclaredResponseType() {
        var responseStage = runtime.send(connection, new PingRequest("ping")).subscribe().asCompletionStage();
        var outboundRequest = connection.singleMessage(PingRequest.class);

        var wrongResponse = new DifferentResponse("not-a-pong");
        wrongResponse.setId(UUID.randomUUID());
        wrongResponse.setCorrelationId(outboundRequest.getId());

        runtime.onMessage(connection.id(), wrongResponse);

        var exception = assertThrows(CompletionException.class, () -> responseStage.toCompletableFuture().join());
        assertInstanceOf(IllegalArgumentException.class, exception.getCause());
        assertTrue(exception.getCause().getMessage().contains(PongResponse.class.getName()));
    }

    @Test
    void turnsRemoteErrorResponsesIntoFailedRequests() {
        var responseStage = runtime.send(connection, new PingRequest("ping")).subscribe().asCompletionStage();
        var outboundRequest = connection.singleMessage(PingRequest.class);

        var errorResponse = new RcpErrorResponse("remote.failure", "boom");
        errorResponse.setId(UUID.randomUUID());
        errorResponse.setCorrelationId(outboundRequest.getId());

        runtime.onMessage(connection.id(), errorResponse);

        var exception = assertThrows(CompletionException.class, () -> responseStage.toCompletableFuture().join());
        var remoteException = assertInstanceOf(RcpRemoteException.class, exception.getCause());
        assertEquals("remote.failure", remoteException.getErrorType());
        assertEquals("boom", remoteException.getDetail());
    }

    @Test
    void retriesUnaryRequestsWithFreshMessagesAndCompletesOnALaterAttempt() {
        runtime = RcpRuntime.<TestConnection>builder(TestConnection::id)
                .responseTimeout(Duration.ofMillis(50))
                .build();
        runtime.onOpen(connection);

        var requestCount = new AtomicInteger();
        var responseStage = runtime.sendWithRetry(
                        connection,
                        () -> new PingRequest("ping-" + requestCount.incrementAndGet()),
                        RcpRetryPolicy.timeoutOnly(3)
                )
                .subscribe()
                .asCompletionStage();

        var firstAttempt = connection.awaitMessage(0, PingRequest.class);
        var secondAttempt = connection.awaitMessage(1, PingRequest.class);

        var response = new PongResponse("pong");
        response.setId(UUID.randomUUID());
        response.setCorrelationId(secondAttempt.getId());
        runtime.onMessage(connection.id(), response);

        assertSame(response, responseStage.toCompletableFuture().join());
        assertEquals(2, requestCount.get());
        assertEquals("ping-1", firstAttempt.payload());
        assertEquals("ping-2", secondAttempt.payload());
        assertTrue(!firstAttempt.getId().equals(secondAttempt.getId()));
    }

    @Test
    void doesNotRetryUnaryRequestsForRemoteFailuresThatDoNotMatchThePolicy() {
        var requestCount = new AtomicInteger();
        var responseStage = runtime.sendWithRetry(
                        connection,
                        () -> new PingRequest("ping-" + requestCount.incrementAndGet()),
                        RcpRetryPolicy.timeoutOnly(3)
                )
                .subscribe()
                .asCompletionStage();

        var outboundRequest = connection.singleMessage(PingRequest.class);

        var errorResponse = new RcpErrorResponse("remote.failure", "boom");
        errorResponse.setId(UUID.randomUUID());
        errorResponse.setCorrelationId(outboundRequest.getId());
        runtime.onMessage(connection.id(), errorResponse);

        var exception = assertThrows(CompletionException.class, () -> responseStage.toCompletableFuture().join());
        assertInstanceOf(RcpRemoteException.class, exception.getCause());
        assertEquals(1, requestCount.get());
        assertEquals(1, connection.sentMessages().size());
    }

    @Test
    void rejectsRetryFactoriesThatReuseTheSameRequestInstance() {
        runtime = RcpRuntime.<TestConnection>builder(TestConnection::id)
                .responseTimeout(Duration.ofMillis(50))
                .build();
        runtime.onOpen(connection);

        var reusedRequest = new PingRequest("ping");
        var responseStage = runtime.sendWithRetry(
                        connection,
                        () -> reusedRequest,
                        RcpRetryPolicy.timeoutOnly(2)
                )
                .subscribe()
                .asCompletionStage();

        connection.awaitMessage(0, PingRequest.class);

        var exception = assertThrows(CompletionException.class, () -> responseStage.toCompletableFuture().join());
        assertInstanceOf(IllegalStateException.class, exception.getCause());
        assertEquals("requestFactory must return a fresh request instance per retry attempt", exception.getCause().getMessage());
    }

    @Test
    void turnsRemoteErrorResponsesIntoFailedStreams() {
        var probe = StreamProbe.capture(runtime.send(connection, new ChunkRequest("chunk")));
        var outboundRequest = connection.singleMessage(ChunkRequest.class);

        var first = new ChunkResponse("chunk:1");
        first.setId(UUID.randomUUID());
        first.setCorrelationId(outboundRequest.getId());
        runtime.onMessage(connection.id(), first);

        var errorResponse = new RcpErrorResponse("remote.stream.failure", "boom");
        errorResponse.setId(UUID.randomUUID());
        errorResponse.setCorrelationId(outboundRequest.getId());
        runtime.onMessage(connection.id(), errorResponse);

        assertEquals(List.of("chunk:1"), probe.payloads(ChunkResponse::payload));

        var failure = probe.awaitFailure();
        var remoteException = assertInstanceOf(RcpRemoteException.class, failure);
        assertEquals("remote.stream.failure", remoteException.getErrorType());
        assertEquals("boom", remoteException.getDetail());
    }

    @Test
    void sendsErrorResponsesWhenHandlersFail() {
        runtime.registerHandler(PingRequest.class, request -> Uni.createFrom().failure(new IllegalStateException("boom")));

        var request = new PingRequest("ping");
        request.setId(UUID.randomUUID());

        runtime.onMessage(connection.id(), request);

        var errorResponse = connection.awaitMessage(0, RcpErrorResponse.class);
        assertNotNull(errorResponse.getId());
        assertEquals(request.getId(), errorResponse.getCorrelationId());
        assertEquals(IllegalStateException.class.getName(), errorResponse.getErrorType());
        assertEquals("boom", errorResponse.getDetail());
    }

    @Test
    void inboundInterceptorsWrapUnaryHandlers() {
        runtime.addInboundInterceptor(new RcpInboundInterceptor<>() {
            @Override
            @SuppressWarnings("unchecked")
            public <TReq extends RcpRequest<TRes>, TRes extends RcpMessage> Uni<TRes> interceptUnary(
                    TestConnection interceptedConnection,
                    UUID connectionId,
                    TReq request,
                    Function<TReq, Uni<TRes>> next
            ) {
                return next.apply((TReq) new PingRequest("server:" + ((PingRequest) request).payload()))
                        .onItem()
                        .transform(response -> (TRes) new PongResponse(((PongResponse) response).payload() + ":server"));
            }
        });
        runtime.registerHandler(PingRequest.class, request -> Uni.createFrom().item(new PongResponse(request.payload())));

        var request = new PingRequest("ping");
        request.setId(UUID.randomUUID());

        runtime.onMessage(connection.id(), request);

        var response = connection.awaitMessage(0, PongResponse.class);
        assertEquals("server:ping:server", response.payload());
    }

    @Test
    void inboundInterceptorsWrapStreamHandlers() {
        runtime.addInboundInterceptor(new RcpInboundInterceptor<>() {
            @Override
            @SuppressWarnings("unchecked")
            public <TReq extends RcpStreamRequest<TRes>, TRes extends RcpMessage> Multi<TRes> interceptStream(
                    TestConnection interceptedConnection,
                    UUID connectionId,
                    TReq request,
                    Function<TReq, Multi<TRes>> next
            ) {
                return next.apply((TReq) new ChunkRequest("server:" + ((ChunkRequest) request).prefix()))
                        .onItem()
                        .transform(item -> (TRes) new ChunkResponse(((ChunkResponse) item).payload() + ":server"));
            }
        });
        runtime.registerStreamHandler(
                ChunkRequest.class,
                request -> Multi.createFrom().items(new ChunkResponse(request.prefix()))
        );

        var request = new ChunkRequest("chunk");
        request.setId(UUID.randomUUID());

        runtime.onMessage(connection.id(), request);

        var item = connection.message(0, ChunkResponse.class);
        assertEquals("server:chunk:server", item.payload());
        var completed = connection.message(1, RcpStreamCompleted.class);
        assertEquals(request.getId(), completed.getCorrelationId());
    }

    @Test
    void sendsErrorResponsesWhenStreamHandlersFail() {
        runtime.registerStreamHandler(
                ChunkRequest.class,
                request -> Multi.createFrom().failure(new IllegalStateException("boom"))
        );

        var request = new ChunkRequest("chunk");
        request.setId(UUID.randomUUID());

        runtime.onMessage(connection.id(), request);

        var errorResponse = connection.singleMessage(RcpErrorResponse.class);
        assertNotNull(errorResponse.getId());
        assertEquals(request.getId(), errorResponse.getCorrelationId());
        assertEquals(IllegalStateException.class.getName(), errorResponse.getErrorType());
        assertEquals("boom", errorResponse.getDetail());
    }

    @Test
    void failsPendingRequestsWhenTheConnectionCloses() {
        var responseStage = runtime.send(connection, new PingRequest("ping")).subscribe().asCompletionStage();
        connection.singleMessage(PingRequest.class);

        runtime.onClose(connection);

        var exception = assertThrows(CompletionException.class, () -> responseStage.toCompletableFuture().join());
        assertInstanceOf(IllegalStateException.class, exception.getCause());
        assertEquals(
                "Connection %s closed while waiting for a response".formatted(connection.id()),
                exception.getCause().getMessage()
        );
    }

    @Test
    void failsPendingStreamsWhenTheConnectionCloses() {
        var probe = StreamProbe.capture(runtime.send(connection, new ChunkRequest("chunk")));
        connection.singleMessage(ChunkRequest.class);

        runtime.onClose(connection);

        var failure = probe.awaitFailure();
        var exception = assertInstanceOf(IllegalStateException.class, failure);
        assertEquals(
                "Connection %s closed while waiting for a response".formatted(connection.id()),
                exception.getMessage()
        );
    }

    @Test
    void builderAppliesConfiguredOutboundInterceptorsAndTimeout() {
        runtime = RcpRuntime.<TestConnection>builder(TestConnection::id)
                .responseTimeout(Duration.ofMillis(50))
                .outboundInterceptor(new RecordingOutboundInterceptor(new ArrayList<>(), "builder"))
                .build();
        runtime.onOpen(connection);

        var responseStage = runtime.send(connection, new PingRequest("ping")).subscribe().asCompletionStage();
        var outboundRequest = connection.awaitMessage(0, PingRequest.class);

        assertEquals("ping|builder", outboundRequest.payload());

        var exception = assertThrows(CompletionException.class, () -> responseStage.toCompletableFuture().join());
        assertInstanceOf(TimeoutException.class, exception.getCause());
    }

    @Test
    void builderAppliesConfiguredInboundInterceptors() {
        runtime = RcpRuntime.<TestConnection>builder(TestConnection::id)
                .inboundInterceptor(new RcpInboundInterceptor<>() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <TReq extends RcpRequest<TRes>, TRes extends RcpMessage> Uni<TRes> interceptUnary(
                            TestConnection interceptedConnection,
                            UUID connectionId,
                            TReq request,
                            Function<TReq, Uni<TRes>> next
                    ) {
                        return next.apply((TReq) new PingRequest("builder:" + ((PingRequest) request).payload()));
                    }
                })
                .build();
        runtime.onOpen(connection);

        runtime.registerHandler(PingRequest.class, request -> Uni.createFrom().item(new PongResponse(request.payload())));

        var request = new PingRequest("ping");
        request.setId(UUID.randomUUID());

        runtime.onMessage(connection.id(), request);

        var response = connection.awaitMessage(0, PongResponse.class);
        assertEquals("builder:ping", response.payload());
    }

    private static final class TestConnection implements RcpConnection {
        private final UUID id;
        private final List<RcpMessage> sentMessages = new ArrayList<>();

        private TestConnection(UUID id) {
            this.id = id;
        }

        private UUID id() {
            return id;
        }

        @Override
        public void send(RcpMessage message) {
            sentMessages.add(message);
        }

        private <T extends RcpMessage> T singleMessage(Class<T> type) {
            assertEquals(1, sentMessages.size());
            return assertInstanceOf(type, sentMessages.getFirst());
        }

        private List<RcpMessage> sentMessages() {
            return List.copyOf(sentMessages);
        }

        private <T extends RcpMessage> T message(int index, Class<T> type) {
            return assertInstanceOf(type, sentMessages.get(index));
        }

        private <T extends RcpMessage> T awaitMessage(int index, Class<T> type) {
            var deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
            while (System.nanoTime() < deadline) {
                if (sentMessages.size() > index) {
                    return assertInstanceOf(type, sentMessages.get(index));
                }

                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted while waiting for message", e);
                }
            }

            throw new AssertionError("Timed out waiting for outbound message " + index);
        }
    }

    private static final class PingRequest extends RcpRequest<PongResponse> {
        private final String payload;

        private PingRequest(String payload) {
            super(PongResponse.class);
            this.payload = payload;
        }

        private String payload() {
            return payload;
        }
    }

    private static final class ChunkRequest extends RcpStreamRequest<ChunkResponse> {
        private final String prefix;

        private ChunkRequest(String prefix) {
            super(ChunkResponse.class);
            this.prefix = prefix;
        }

        private String prefix() {
            return prefix;
        }
    }

    private static final class PongResponse extends RcpMessage {
        private final String payload;

        private PongResponse(String payload) {
            this.payload = payload;
        }

        private String payload() {
            return payload;
        }
    }

    private static final class ChunkResponse extends RcpMessage {
        private final String payload;

        private ChunkResponse(String payload) {
            this.payload = payload;
        }

        private String payload() {
            return payload;
        }
    }

    private static final class DifferentResponse extends RcpMessage {
        private final String payload;

        private DifferentResponse(String payload) {
            this.payload = payload;
        }

        private String payload() {
            return payload;
        }
    }

    private static final class StreamProbe<T> {
        private final List<T> items = new CopyOnWriteArrayList<>();
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private final CompletableFuture<Throwable> failure = new CompletableFuture<>();

        private static <T> StreamProbe<T> capture(Multi<T> stream) {
            var probe = new StreamProbe<T>();
            stream.subscribe().with(
                    probe.items::add,
                    probe.failure::complete,
                    () -> probe.completion.complete(null)
            );
            return probe;
        }

        private void awaitCompletion() {
            completion.join();
        }

        private Throwable awaitFailure() {
            return failure.join();
        }

        private <R> List<R> payloads(java.util.function.Function<T, R> mapper) {
            return items.stream().map(mapper).toList();
        }
    }

    private static final class RecordingOutboundInterceptor implements RcpOutboundInterceptor<TestConnection> {
        private final List<String> events;
        private final String name;

        private RecordingOutboundInterceptor(List<String> events, String name) {
            this.events = events;
            this.name = name;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <TReq extends RcpRequest<TRes>, TRes extends RcpMessage> io.smallrye.mutiny.Uni<TRes> interceptUnary(
                TestConnection interceptedConnection,
                UUID connectionId,
                TReq request,
                Function<TReq, io.smallrye.mutiny.Uni<TRes>> next
        ) {
            events.add(name + ":before:" + ((PingRequest) request).payload());
            return next.apply((TReq) new PingRequest(((PingRequest) request).payload() + "|" + name))
                    .onItem()
                    .transform(item -> {
                        events.add(name + ":after:" + ((PongResponse) item).payload());
                        return (TRes) new PongResponse(((PongResponse) item).payload() + "|" + name);
                    });
        }
    }
}
