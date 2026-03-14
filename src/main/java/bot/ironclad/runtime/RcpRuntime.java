package bot.ironclad.runtime;

import bot.ironclad.connection.RcpConnection;
import bot.ironclad.handler.MessageHandler;
import bot.ironclad.handler.StreamMessageHandler;
import bot.ironclad.protocol.RcpErrorResponse;
import bot.ironclad.protocol.RcpMessage;
import bot.ironclad.protocol.RcpRequest;
import bot.ironclad.protocol.RcpStreamCompleted;
import bot.ironclad.protocol.RcpStreamRequest;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

public class RcpRuntime<T extends RcpConnection> {
    private static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    private final RcpMessageHandlerRegistry handlerRegistry = new RcpMessageHandlerRegistry();
    private final ConcurrentHashMap<UUID, PendingResponse<?>> pendingResponses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, T> connections = new ConcurrentHashMap<>();
    private final Function<T, UUID> connectionIdProvider;
    private final Duration responseTimeout;

    public RcpRuntime(Function<T, UUID> connectionIdProvider) {
        this(connectionIdProvider, DEFAULT_RESPONSE_TIMEOUT);
    }

    public RcpRuntime(Function<T, UUID> connectionIdProvider, Duration responseTimeout) {
        this.connectionIdProvider = Objects.requireNonNull(connectionIdProvider, "connectionIdProvider");
        this.responseTimeout = Objects.requireNonNull(responseTimeout, "responseTimeout");
    }

    public void onOpen(T connection) {
        connections.put(resolveConnectionId(connection), connection);
    }

    public void onClose(T connection) {
        onClose(resolveConnectionId(connection));
    }

    public void onClose(UUID id) {
        connections.remove(id);
        pendingResponses.forEach((requestId, pendingResponse) -> {
            if (pendingResponse.connectionId().equals(id) && pendingResponses.remove(requestId, pendingResponse)) {
                pendingResponse.fail(new IllegalStateException(
                        "Connection %s closed while waiting for a response".formatted(id)
                ));
            }
        });
    }

    public void onMessage(UUID senderId, RcpMessage message) {
        if (message.getCorrelationId() != null) {
            completePendingResponse(message);
            return;
        }

        if (message instanceof RcpStreamRequest<?> request) {
            handleIncomingStreamRequest(senderId, request);
            return;
        }

        if (!(message instanceof RcpRequest<?> request)) {
            throw new IllegalArgumentException(
                    "Inbound messages without a correlation id must extend %s".formatted(RcpRequest.class.getName())
            );
        }

        handleIncomingRequest(senderId, request);
    }

    public <TReq extends RcpRequest<TRes>, TRes extends RcpMessage> Uni<TRes> send(
            UUID connectionId,
            TReq request
    ) {
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(request, "request");
        ensureMessageId(request);

        return Uni.createFrom().<TRes>emitter(emitter -> {
            var connection = connections.get(connectionId);
            if (connection == null) {
                emitter.fail(new IllegalStateException("No connection for connectionId " + connectionId));
                return;
            }

            var requestId = request.getId();
            var pendingResponse = new PendingSingleResponse<>(connectionId, request.getResponseType(), emitter);

            if (pendingResponses.putIfAbsent(requestId, pendingResponse) != null) {
                emitter.fail(new IllegalStateException("A request with id %s is already pending".formatted(requestId)));
                return;
            }

            emitter.onTermination(() -> pendingResponses.remove(requestId, pendingResponse));

            try {
                connection.send(request);
            } catch (Throwable t) {
                pendingResponses.remove(requestId, pendingResponse);
                emitter.fail(t);
            }
        }).ifNoItem()
                .after(responseTimeout)
                .failWith(() -> new TimeoutException(
                        "Timed out waiting for %s".formatted(request.getResponseType().getName())
                ));
    }

    public <TReq extends RcpStreamRequest<TRes>, TRes extends RcpMessage> Multi<TRes> send(
            UUID connectionId,
            TReq request
    ) {
        return sendStream(connectionId, request);
    }

    public <TReq extends RcpRequest<TRes>, TRes extends RcpMessage> Uni<TRes> send(
            T connection,
            TReq request
    ) {
        return send(resolveConnectionId(connection), request);
    }

    public <TReq extends RcpStreamRequest<TRes>, TRes extends RcpMessage> Multi<TRes> send(
            T connection,
            TReq request
    ) {
        return sendStream(resolveConnectionId(connection), request);
    }

    public <TReq extends RcpRequest<TRes>, TRes extends RcpMessage> Uni<TRes> sendMessageAndGetResponse(
            UUID connectionId,
            TReq request
    ) {
        return send(connectionId, request);
    }

    public <TReq extends RcpStreamRequest<TRes>, TRes extends RcpMessage> Multi<TRes> sendMessageAndGetResponses(
            UUID connectionId,
            TReq request
    ) {
        return sendStream(connectionId, request);
    }

    public <TReq extends RcpRequest<TRes>, TRes extends RcpMessage> void registerHandler(
            Class<TReq> requestType,
            MessageHandler<TReq, TRes> handler
    ) {
        handlerRegistry.registerHandler(requestType, handler);
    }

    public <TReq extends RcpStreamRequest<TRes>, TRes extends RcpMessage> void registerStreamHandler(
            Class<TReq> requestType,
            StreamMessageHandler<TReq, TRes> handler
    ) {
        handlerRegistry.registerStreamHandler(requestType, handler);
    }

    private UUID resolveConnectionId(T connection) {
        Objects.requireNonNull(connection, "connection");
        var connectionId = connectionIdProvider.apply(connection);
        if (connectionId == null) {
            throw new IllegalArgumentException("connectionIdProvider returned null");
        }
        return connectionId;
    }

    private void handleIncomingRequest(UUID senderId, RcpRequest<?> request) {
        if (request.getId() == null) {
            throw new IllegalArgumentException(
                    "Incoming request message must have a non-null id: " + request.getClass().getName()
            );
        }

        var connection = connections.get(senderId);
        if (connection == null) {
            throw new IllegalStateException("No connection for senderId " + senderId);
        }

        var response = safelyHandle(request);
        ensureMessageId(response);
        response.setCorrelationId(request.getId());
        connection.send(response);
    }

    private void handleIncomingStreamRequest(UUID senderId, RcpStreamRequest<?> request) {
        if (request.getId() == null) {
            throw new IllegalArgumentException(
                    "Incoming request message must have a non-null id: " + request.getClass().getName()
            );
        }

        var connection = connections.get(senderId);
        if (connection == null) {
            throw new IllegalStateException("No connection for senderId " + senderId);
        }

        safelyHandle(request).subscribe().with(
                item -> sendCorrelatedMessage(connection, request.getId(), item),
                failure -> sendCorrelatedMessage(connection, request.getId(), RcpErrorResponse.from(failure)),
                () -> sendCorrelatedMessage(connection, request.getId(), new RcpStreamCompleted())
        );
    }

    private RcpMessage safelyHandle(RcpRequest<?> request) {
        try {
            return handlerRegistry.handle(request);
        } catch (Throwable throwable) {
            return RcpErrorResponse.from(throwable);
        }
    }

    private Multi<? extends RcpMessage> safelyHandle(RcpStreamRequest<?> request) {
        try {
            return handlerRegistry.handle(request);
        } catch (Throwable throwable) {
            return Multi.createFrom().failure(throwable);
        }
    }

    private void completePendingResponse(RcpMessage message) {
        var pendingResponse = pendingResponses.get(message.getCorrelationId());
        if (pendingResponse == null) {
            return;
        }

        if (pendingResponse.accept(message)) {
            pendingResponses.remove(message.getCorrelationId(), pendingResponse);
        }
    }

    private <TReq extends RcpStreamRequest<TRes>, TRes extends RcpMessage> Multi<TRes> sendStream(
            UUID connectionId,
            TReq request
    ) {
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(request, "request");
        ensureMessageId(request);

        return Multi.createFrom().<TRes>emitter(emitter -> {
            var connection = connections.get(connectionId);
            if (connection == null) {
                emitter.fail(new IllegalStateException("No connection for connectionId " + connectionId));
                return;
            }

            var requestId = request.getId();
            var pendingResponse = new PendingStreamResponse<>(connectionId, request.getResponseType(), emitter);

            if (pendingResponses.putIfAbsent(requestId, pendingResponse) != null) {
                emitter.fail(new IllegalStateException("A request with id %s is already pending".formatted(requestId)));
                return;
            }

            emitter.onTermination(() -> pendingResponses.remove(requestId, pendingResponse));

            try {
                connection.send(request);
            } catch (Throwable throwable) {
                pendingResponses.remove(requestId, pendingResponse);
                emitter.fail(throwable);
            }
        }).ifNoItem()
                .after(responseTimeout)
                .failWith(() -> new TimeoutException(
                        "Timed out waiting for stream item %s".formatted(request.getResponseType().getName())
                ));
    }

    private static void sendCorrelatedMessage(RcpConnection connection, UUID correlationId, RcpMessage message) {
        ensureMessageId(message);
        message.setCorrelationId(correlationId);
        connection.send(message);
    }

    private static void ensureMessageId(RcpMessage message) {
        if (message.getId() == null) {
            message.setId(UUID.randomUUID());
        }
    }
}
