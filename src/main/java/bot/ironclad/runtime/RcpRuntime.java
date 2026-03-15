package bot.ironclad.runtime;

import bot.ironclad.connection.RcpConnection;
import bot.ironclad.handler.MessageHandler;
import bot.ironclad.handler.StreamMessageHandler;
import bot.ironclad.interceptor.RcpInboundInterceptor;
import bot.ironclad.interceptor.RcpOutboundInterceptor;
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
import java.util.ArrayList;
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
    private final CopyOnWriteArrayList<RcpOutboundInterceptor<T>> outboundInterceptors = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<RcpInboundInterceptor<T>> inboundInterceptors = new CopyOnWriteArrayList<>();
    private final Function<T, UUID> connectionIdProvider;
    private final Duration responseTimeout;

    public static <T extends RcpConnection> Builder<T> builder(Function<T, UUID> connectionIdProvider) {
        return new Builder<>(connectionIdProvider);
    }

    public RcpRuntime(Function<T, UUID> connectionIdProvider) {
        this(new Builder<>(connectionIdProvider));
    }

    public RcpRuntime(Function<T, UUID> connectionIdProvider, Duration responseTimeout) {
        this(new Builder<>(connectionIdProvider).responseTimeout(responseTimeout));
    }

    private RcpRuntime(Builder<T> builder) {
        this.connectionIdProvider = Objects.requireNonNull(builder.connectionIdProvider, "connectionIdProvider");
        this.responseTimeout = requireResponseTimeout(builder.responseTimeout);
        this.outboundInterceptors.addAll(builder.outboundInterceptors);
        this.inboundInterceptors.addAll(builder.inboundInterceptors);
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
            return invokeOutboundUnaryInterceptors(connection, connectionId, request, 0);
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
            return invokeOutboundStreamInterceptors(connection, connectionId, request, 0);
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

    public void addOutboundInterceptor(RcpOutboundInterceptor<T> interceptor) {
        outboundInterceptors.add(Objects.requireNonNull(interceptor, "interceptor"));
    }

    public void addInboundInterceptor(RcpInboundInterceptor<T> interceptor) {
        inboundInterceptors.add(Objects.requireNonNull(interceptor, "interceptor"));
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

        safelyHandleInboundUnary(connection, senderId, request).subscribe().with(
                item -> sendCorrelatedMessage(connection, request.getId(), item),
                failure -> sendCorrelatedMessage(connection, request.getId(), RcpErrorResponse.from(failure))
        );
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

        safelyHandleInboundStream(connection, senderId, request).subscribe().with(
                item -> sendCorrelatedMessage(connection, request.getId(), item),
                failure -> sendCorrelatedMessage(connection, request.getId(), RcpErrorResponse.from(failure)),
                () -> sendCorrelatedMessage(connection, request.getId(), new RcpStreamCompleted())
        );
    }

    private <TReq extends RcpRequest<TRes>, TRes extends RcpMessage> Uni<? extends RcpMessage> safelyHandleInboundUnary(
            T connection,
            UUID connectionId,
            TReq request
    ) {
        try {
            return invokeInboundUnaryInterceptors(connection, connectionId, request, 0);
        } catch (Throwable throwable) {
            return Uni.createFrom().failure(throwable);
        }
    }

    private <TReq extends RcpStreamRequest<TRes>, TRes extends RcpMessage> Multi<? extends RcpMessage> safelyHandleInboundStream(
            T connection,
            UUID connectionId,
            TReq request
    ) {
        try {
            return invokeInboundStreamInterceptors(connection, connectionId, request, 0);
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

    private <TReq extends RcpRequest<TRes>, TRes extends RcpMessage> Uni<TRes> invokeOutboundUnaryInterceptors(
            T connection,
            UUID connectionId,
            TReq request,
            int interceptorIndex
    ) {
        if (interceptorIndex == outboundInterceptors.size()) {
            return sendUnaryInternal(connectionId, request);
        }

        return outboundInterceptors.get(interceptorIndex).interceptUnary(
                connection,
                connectionId,
                request,
                nextRequest -> invokeOutboundUnaryInterceptors(connection, connectionId, nextRequest, interceptorIndex + 1)
        );
    }

    private <TReq extends RcpStreamRequest<TRes>, TRes extends RcpMessage> Multi<TRes> invokeOutboundStreamInterceptors(
            T connection,
            UUID connectionId,
            TReq request,
            int interceptorIndex
    ) {
        if (interceptorIndex == outboundInterceptors.size()) {
            return sendStream(connectionId, request);
        }

        return outboundInterceptors.get(interceptorIndex).interceptStream(
                connection,
                connectionId,
                request,
                nextRequest -> invokeOutboundStreamInterceptors(connection, connectionId, nextRequest, interceptorIndex + 1)
        );
    }

    private <TReq extends RcpRequest<TRes>, TRes extends RcpMessage> Uni<TRes> invokeInboundUnaryInterceptors(
            T connection,
            UUID connectionId,
            TReq request,
            int interceptorIndex
    ) {
        if (interceptorIndex == inboundInterceptors.size()) {
            return handlerRegistry.handle(request);
        }

        return Objects.requireNonNull(inboundInterceptors.get(interceptorIndex).interceptUnary(
                connection,
                connectionId,
                request,
                nextRequest -> invokeInboundUnaryInterceptors(connection, connectionId, nextRequest, interceptorIndex + 1)
        ), "inbound interceptor returned null");
    }

    private <TReq extends RcpStreamRequest<TRes>, TRes extends RcpMessage> Multi<TRes> invokeInboundStreamInterceptors(
            T connection,
            UUID connectionId,
            TReq request,
            int interceptorIndex
    ) {
        if (interceptorIndex == inboundInterceptors.size()) {
            return handlerRegistry.handle(request);
        }

        return inboundInterceptors.get(interceptorIndex).interceptStream(
                connection,
                connectionId,
                request,
                nextRequest -> invokeInboundStreamInterceptors(connection, connectionId, nextRequest, interceptorIndex + 1)
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

    private static Duration requireResponseTimeout(Duration responseTimeout) {
        var timeout = Objects.requireNonNull(responseTimeout, "responseTimeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("responseTimeout must not be negative");
        }
        return timeout;
    }

    public static final class Builder<T extends RcpConnection> {
        private final Function<T, UUID> connectionIdProvider;
        private final ArrayList<RcpOutboundInterceptor<T>> outboundInterceptors = new ArrayList<>();
        private final ArrayList<RcpInboundInterceptor<T>> inboundInterceptors = new ArrayList<>();
        private Duration responseTimeout = DEFAULT_RESPONSE_TIMEOUT;

        private Builder(Function<T, UUID> connectionIdProvider) {
            this.connectionIdProvider = Objects.requireNonNull(connectionIdProvider, "connectionIdProvider");
        }

        public Builder<T> responseTimeout(Duration responseTimeout) {
            this.responseTimeout = requireResponseTimeout(responseTimeout);
            return this;
        }

        public Builder<T> outboundInterceptor(RcpOutboundInterceptor<T> interceptor) {
            outboundInterceptors.add(Objects.requireNonNull(interceptor, "interceptor"));
            return this;
        }

        public Builder<T> outboundInterceptors(Iterable<? extends RcpOutboundInterceptor<T>> interceptors) {
            Objects.requireNonNull(interceptors, "interceptors");
            interceptors.forEach(this::outboundInterceptor);
            return this;
        }

        public Builder<T> inboundInterceptor(RcpInboundInterceptor<T> interceptor) {
            inboundInterceptors.add(Objects.requireNonNull(interceptor, "interceptor"));
            return this;
        }

        public Builder<T> inboundInterceptors(Iterable<? extends RcpInboundInterceptor<T>> interceptors) {
            Objects.requireNonNull(interceptors, "interceptors");
            interceptors.forEach(this::inboundInterceptor);
            return this;
        }

        public RcpRuntime<T> build() {
            return new RcpRuntime<>(this);
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
