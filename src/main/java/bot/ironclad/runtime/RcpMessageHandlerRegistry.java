package bot.ironclad.runtime;

import bot.ironclad.handler.MessageHandler;
import bot.ironclad.handler.StreamMessageHandler;
import bot.ironclad.protocol.RcpMessage;
import bot.ironclad.protocol.RcpRequest;
import bot.ironclad.protocol.RcpStreamRequest;
import io.smallrye.mutiny.Multi;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class RcpMessageHandlerRegistry {
    private final ConcurrentHashMap<Class<? extends RcpRequest<?>>, RegisteredHandler<?, ?>> handlers;
    private final ConcurrentHashMap<Class<? extends RcpStreamRequest<?>>, RegisteredStreamHandler<?, ?>> streamHandlers;

    public RcpMessageHandlerRegistry() {
        handlers = new ConcurrentHashMap<>();
        streamHandlers = new ConcurrentHashMap<>();
    }

    public <TReq extends RcpRequest<TRes>, TRes extends RcpMessage> void registerHandler(
            Class<TReq> messageType,
            MessageHandler<TReq, TRes> handler
    ) {
        handlers.put(
                Objects.requireNonNull(messageType, "messageType"),
                new RegisteredHandler<>(messageType, handler)
        );
    }

    public <TReq extends RcpRequest<TRes>, TRes extends RcpMessage> TRes handle(TReq request) {
        return getHandler(request).handle(request);
    }

    public <TReq extends RcpStreamRequest<TRes>, TRes extends RcpMessage> Multi<TRes> handle(TReq request) {
        return getStreamHandler(request).handle(request);
    }

    public <TReq extends RcpStreamRequest<TRes>, TRes extends RcpMessage> void registerStreamHandler(
            Class<TReq> messageType,
            StreamMessageHandler<TReq, TRes> handler
    ) {
        streamHandlers.put(
                Objects.requireNonNull(messageType, "messageType"),
                new RegisteredStreamHandler<>(messageType, handler)
        );
    }

    @SuppressWarnings("unchecked")
    private <TReq extends RcpRequest<TRes>, TRes extends RcpMessage> RegisteredHandler<TReq, TRes> getHandler(TReq request) {
        var messageType = (Class<TReq>) request.getClass();
        var handler = (RegisteredHandler<TReq, TRes>) handlers.get(messageType);

        if (handler == null) {
            throw new IllegalArgumentException("No handler registered for " + messageType.getName());
        }

        return handler;
    }

    @SuppressWarnings("unchecked")
    private <TReq extends RcpStreamRequest<TRes>, TRes extends RcpMessage> RegisteredStreamHandler<TReq, TRes> getStreamHandler(TReq request) {
        var messageType = (Class<TReq>) request.getClass();
        var handler = (RegisteredStreamHandler<TReq, TRes>) streamHandlers.get(messageType);

        if (handler == null) {
            throw new IllegalArgumentException("No stream handler registered for " + messageType.getName());
        }

        return handler;
    }

    private static final class RegisteredHandler<TReq extends RcpRequest<TRes>, TRes extends RcpMessage> {
        private final Class<TReq> messageType;
        private final MessageHandler<TReq, TRes> handler;

        private RegisteredHandler(Class<TReq> messageType, MessageHandler<TReq, TRes> handler) {
            this.messageType = Objects.requireNonNull(messageType, "messageType");
            this.handler = Objects.requireNonNull(handler, "handler");
        }

        private TRes handle(RcpRequest<?> request) {
            return Objects.requireNonNull(
                    handler.handle(messageType.cast(request)),
                    () -> "Handler returned null for " + messageType.getName()
            );
        }
    }

    private static final class RegisteredStreamHandler<TReq extends RcpStreamRequest<TRes>, TRes extends RcpMessage> {
        private final Class<TReq> messageType;
        private final StreamMessageHandler<TReq, TRes> handler;

        private RegisteredStreamHandler(Class<TReq> messageType, StreamMessageHandler<TReq, TRes> handler) {
            this.messageType = Objects.requireNonNull(messageType, "messageType");
            this.handler = Objects.requireNonNull(handler, "handler");
        }

        private Multi<TRes> handle(RcpStreamRequest<?> request) {
            return Objects.requireNonNull(
                    handler.handle(messageType.cast(request)),
                    () -> "Stream handler returned null for " + messageType.getName()
            );
        }
    }
}
