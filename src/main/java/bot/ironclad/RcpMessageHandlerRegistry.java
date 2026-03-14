package bot.ironclad;

import java.util.concurrent.ConcurrentHashMap;

public class RcpMessageHandlerRegistry {
    private final ConcurrentHashMap<Class<? extends RcpMessage>, MessageHandler<RcpMessage, RcpMessage>> handlers;

    public RcpMessageHandlerRegistry() {
        handlers = new ConcurrentHashMap<>();
    }

    public void register(Class<? extends RcpMessage> messageType, MessageHandler<RcpMessage, RcpMessage> handler) {
        handlers.put(messageType, handler);
    }

    public MessageHandler<RcpMessage, RcpMessage> getHandler(Class<? extends RcpMessage> messageType) {
        return handlers.get(messageType);
    }
}
