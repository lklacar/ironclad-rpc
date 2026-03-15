package bot.ironclad.runtime;

import bot.ironclad.connection.RcpConnection;
import bot.ironclad.handler.MessageHandler;
import bot.ironclad.handler.StreamMessageHandler;
import bot.ironclad.interceptor.RcpClientInterceptor;
import bot.ironclad.interceptor.RcpServerInterceptor;
import bot.ironclad.protocol.RcpErrorResponse;
import bot.ironclad.protocol.RcpMessage;
import bot.ironclad.protocol.RcpRequest;
import bot.ironclad.protocol.RcpStreamCompleted;
import bot.ironclad.protocol.RcpStreamRequest;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.Cancellable;
import io.smallrye.mutiny.subscription.UniEmitter;

import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

public class RcpRuntime<T extends RcpConnection> {
    private static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    private final RcpMessageHandlerRegistry handlerRegistry = new RcpMessageHandlerRegistry();
    private final ConcurrentHashMap<UUID, PendingResponse<?>> pendingResponses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, T> connections = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<RcpClientInterceptor<T>> clientInterceptors = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<RcpServerInterceptor<T>> serverInterceptors = new CopyOnWriteArrayList<>();
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

        var connection = connections.get(connectionId);
        if (connection == null) {
            return Uni.createFrom().failure(new IllegalStateException("No connection for connectionId " + connectionId));
        }

        try {
            return invokeClientUnaryInterceptors(connection, connectionId, request, 0);
        } catch (Throwable throwable) {
            return Uni.createFrom().failure(throwable);
        }
    }

    public <TReq extends RcpStreamRequest<TRes>, TRes extends RcpMessage> Multi<TRes> send(
            UUID connectionId,
            TReq request
    ) {
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(request, "request");
        ensureMessageId(request);

        var connection = connections.get(connectionId);
        if (connection == null) {
            return Multi.createFrom().failure(new IllegalStateException("No connection for connectionId " + connectionId));
        }

        try {
            return invokeClientStreamInterceptors(connection, connectionId, request, 0);
        } catch (Throwable throwable) {
            return Multi.createFrom().failure(throwable);
        }
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
        return send(resolveConnectionId(connection), request);
    }

    public <TReq extends RcpRequest<TRes>, TRes extends RcpMessage> Uni<TRes> sendMessageAndGetResponse(
            UUID connectionId,
            TReq request
    ) {
        return send(connectionId, request);
    }

    public <TReq extends RcpRequest<TRes>, TRes extends RcpMessage> Uni<TRes> sendWithRetry(
            UUID connectionId,
            Supplier<TReq> requestFactory,
            RcpRetryPolicy retryPolicy
    ) {
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(requestFactory, "requestFactory");
        Objects.requireNonNull(retryPolicy, "retryPolicy");

        return Uni.createFrom().emitter(emitter -> {
            var retryState = new RetryState<TReq>();
            emitter.onTermination(retryState::terminate);
            attemptSendWithRetry(connectionId, requestFactory, retryPolicy, retryState, emitter);
        });
    }

    public <TReq extends RcpRequest<TRes>, TRes extends RcpMessage> Uni<TRes> sendWithRetry(
            T connection,
            Supplier<TReq> requestFactory,
            RcpRetryPolicy retryPolicy
    ) {
        return sendWithRetry(resolveConnectionId(connection), requestFactory, retryPolicy);
    }

    public <TReq extends RcpStreamRequest<TRes>, TRes extends RcpMessage> Multi<TRes> sendMessageAndGetResponses(
            UUID connectionId,
            TReq request
    ) {
        return send(connectionId, request);
    }

    public <TReq extends RcpRequest<TRes>, TRes extends RcpMessage> Uni<TRes> sendMessageAndGetResponseWithRetry(
            UUID connectionId,
            Supplier<TReq> requestFactory,
            RcpRetryPolicy retryPolicy
    ) {
        return sendWithRetry(connectionId, requestFactory, retryPolicy);
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

    public void addClientInterceptor(RcpClientInterceptor<T> interceptor) {
        clientInterceptors.add(Objects.requireNonNull(interceptor, "interceptor"));
    }

    public void addServerInterceptor(RcpServerInterceptor<T> interceptor) {
        serverInterceptors.add(Objects.requireNonNull(interceptor, "interceptor"));
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

        var response = safelyHandle(connection, senderId, request);
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

        safelyHandle(connection, senderId, request).subscribe().with(
                item -> sendCorrelatedMessage(connection, request.getId(), item),
                failure -> sendCorrelatedMessage(connection, request.getId(), RcpErrorResponse.from(failure)),
                () -> sendCorrelatedMessage(connection, request.getId(), new RcpStreamCompleted())
        );
    }

    private <TReq extends RcpRequest<TRes>, TRes extends RcpMessage> RcpMessage safelyHandle(
            T connection,
            UUID connectionId,
            TReq request
    ) {
        try {
            return invokeServerUnaryInterceptors(connection, connectionId, request, 0);
        } catch (Throwable throwable) {
            return RcpErrorResponse.from(throwable);
        }
    }

    private <TReq extends RcpStreamRequest<TRes>, TRes extends RcpMessage> Multi<? extends RcpMessage> safelyHandle(
            T connection,
            UUID connectionId,
            TReq request
    ) {
        try {
            return invokeServerStreamInterceptors(connection, connectionId, request, 0);
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

    private <TReq extends RcpRequest<TRes>, TRes extends RcpMessage> Uni<TRes> invokeClientUnaryInterceptors(
            T connection,
            UUID connectionId,
            TReq request,
            int interceptorIndex
    ) {
        if (interceptorIndex == clientInterceptors.size()) {
            return sendUnaryInternal(connectionId, request);
        }

        return clientInterceptors.get(interceptorIndex).interceptUnary(
                connection,
                connectionId,
                request,
                nextRequest -> invokeClientUnaryInterceptors(connection, connectionId, nextRequest, interceptorIndex + 1)
        );
    }

    private <TReq extends RcpStreamRequest<TRes>, TRes extends RcpMessage> Multi<TRes> invokeClientStreamInterceptors(
            T connection,
            UUID connectionId,
            TReq request,
            int interceptorIndex
    ) {
        if (interceptorIndex == clientInterceptors.size()) {
            return sendStream(connectionId, request);
        }

        return clientInterceptors.get(interceptorIndex).interceptStream(
                connection,
                connectionId,
                request,
                nextRequest -> invokeClientStreamInterceptors(connection, connectionId, nextRequest, interceptorIndex + 1)
        );
    }

    private <TReq extends RcpRequest<TRes>, TRes extends RcpMessage> TRes invokeServerUnaryInterceptors(
            T connection,
            UUID connectionId,
            TReq request,
            int interceptorIndex
    ) {
        if (interceptorIndex == serverInterceptors.size()) {
            return handlerRegistry.handle(request);
        }

        return serverInterceptors.get(interceptorIndex).interceptUnary(
                connection,
                connectionId,
                request,
                nextRequest -> invokeServerUnaryInterceptors(connection, connectionId, nextRequest, interceptorIndex + 1)
        );
    }

    private <TReq extends RcpStreamRequest<TRes>, TRes extends RcpMessage> Multi<TRes> invokeServerStreamInterceptors(
            T connection,
            UUID connectionId,
            TReq request,
            int interceptorIndex
    ) {
        if (interceptorIndex == serverInterceptors.size()) {
            return handlerRegistry.handle(request);
        }

        return serverInterceptors.get(interceptorIndex).interceptStream(
                connection,
                connectionId,
                request,
                nextRequest -> invokeServerStreamInterceptors(connection, connectionId, nextRequest, interceptorIndex + 1)
        );
    }

    private <TReq extends RcpRequest<TRes>, TRes extends RcpMessage> Uni<TRes> sendUnaryInternal(
            UUID connectionId,
            TReq request
    ) {
        ensureMessageId(request);

        return Uni.createFrom().<TRes>emitter(emitter -> {
            var requestId = request.getId();
            var pendingResponse = new PendingSingleResponse<>(connectionId, request.getResponseType(), emitter);

            if (pendingResponses.putIfAbsent(requestId, pendingResponse) != null) {
                emitter.fail(new IllegalStateException("A request with id %s is already pending".formatted(requestId)));
                return;
            }

            emitter.onTermination(() -> pendingResponses.remove(requestId, pendingResponse));

            try {
                connections.get(connectionId).send(request);
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

    private <TReq extends RcpRequest<TRes>, TRes extends RcpMessage> void attemptSendWithRetry(
            UUID connectionId,
            Supplier<TReq> requestFactory,
            RcpRetryPolicy retryPolicy,
            RetryState<TReq> retryState,
            UniEmitter<? super TRes> emitter
    ) {
        if (retryState.isTerminated()) {
            return;
        }

        final int attempt;
        final TReq request;

        try {
            attempt = retryState.nextAttempt();
            request = nextRetryRequest(requestFactory, retryState);
        } catch (Throwable throwable) {
            emitter.fail(throwable);
            return;
        }

        retryState.track(send(connectionId, request).subscribe().with(
                emitter::complete,
                failure -> {
                    if (retryState.isTerminated()) {
                        return;
                    }

                    if (!retryPolicy.shouldRetry(attempt, failure)) {
                        emitter.fail(failure);
                        return;
                    }

                    scheduleRetry(connectionId, requestFactory, retryPolicy, retryState, emitter, attempt, failure);
                }
        ));
    }

    private <TReq extends RcpRequest<TRes>, TRes extends RcpMessage> void scheduleRetry(
            UUID connectionId,
            Supplier<TReq> requestFactory,
            RcpRetryPolicy retryPolicy,
            RetryState<TReq> retryState,
            UniEmitter<? super TRes> emitter,
            int attempt,
            Throwable failure
    ) {
        var delay = retryPolicy.delayBeforeRetry(attempt, failure);
        if (delay.isZero()) {
            attemptSendWithRetry(connectionId, requestFactory, retryPolicy, retryState, emitter);
            return;
        }

        retryState.track(Uni.createFrom()
                .item(Boolean.TRUE)
                .onItem()
                .delayIt()
                .by(delay)
                .subscribe()
                .with(
                        ignored -> attemptSendWithRetry(connectionId, requestFactory, retryPolicy, retryState, emitter),
                        emitter::fail
                ));
    }

    private static <TReq extends RcpRequest<?>> TReq nextRetryRequest(
            Supplier<TReq> requestFactory,
            RetryState<TReq> retryState
    ) {
        var request = Objects.requireNonNull(requestFactory.get(), "requestFactory returned null");

        if (!retryState.markSeen(request)) {
            throw new IllegalStateException("requestFactory must return a fresh request instance per retry attempt");
        }

        request.setCorrelationId(null);
        request.setId(UUID.randomUUID());
        return request;
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

    private static final class RetryState<TReq extends RcpRequest<?>> {
        private final Set<TReq> seenRequests = Collections.newSetFromMap(new IdentityHashMap<>());
        private int attempts;
        private boolean terminated;
        private Cancellable currentSubscription;

        private int nextAttempt() {
            attempts += 1;
            return attempts;
        }

        private boolean markSeen(TReq request) {
            return seenRequests.add(request);
        }

        private synchronized void track(Cancellable cancellable) {
            if (terminated) {
                cancellable.cancel();
                return;
            }
            currentSubscription = cancellable;
        }

        private synchronized void terminate() {
            terminated = true;
            if (currentSubscription != null) {
                currentSubscription.cancel();
            }
        }

        private synchronized boolean isTerminated() {
            return terminated;
        }
    }
}
