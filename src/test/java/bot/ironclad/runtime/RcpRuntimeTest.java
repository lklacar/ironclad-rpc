package bot.ironclad.runtime;

import bot.ironclad.connection.RcpConnection;
import bot.ironclad.protocol.RcpErrorResponse;
import bot.ironclad.protocol.RcpMessage;
import bot.ironclad.protocol.RcpRequest;
import bot.ironclad.protocol.RcpStreamCompleted;
import bot.ironclad.protocol.RcpStreamRequest;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

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
        runtime = new RcpRuntime<>(TestConnection::id, Duration.ofSeconds(1));
        runtime.onOpen(connection);
    }

    @Test
    void dispatchesIncomingRequestsToTheirTypedHandler() {
        runtime.registerHandler(PingRequest.class, request -> new PongResponse("pong:" + request.payload()));

        var request = new PingRequest("ping");
        request.setId(UUID.randomUUID());

        runtime.onMessage(connection.id(), request);

        var response = connection.singleMessage(PongResponse.class);
        assertNotNull(response.getId());
        assertEquals(request.getId(), response.getCorrelationId());
        assertEquals("pong:ping", response.payload());
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
        runtime = new RcpRuntime<>(TestConnection::id, Duration.ofMillis(50));
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
        runtime = new RcpRuntime<>(TestConnection::id, Duration.ofMillis(50));
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
        runtime.registerHandler(PingRequest.class, request -> {
            throw new IllegalStateException("boom");
        });

        var request = new PingRequest("ping");
        request.setId(UUID.randomUUID());

        runtime.onMessage(connection.id(), request);

        var errorResponse = connection.singleMessage(RcpErrorResponse.class);
        assertNotNull(errorResponse.getId());
        assertEquals(request.getId(), errorResponse.getCorrelationId());
        assertEquals(IllegalStateException.class.getName(), errorResponse.getErrorType());
        assertEquals("boom", errorResponse.getDetail());
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
}
