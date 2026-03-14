package bot.ironclad;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.UniEmitter;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RcpRuntime {

    private final RcpMessageHandlerRegistry handlerRegistry = new RcpMessageHandlerRegistry();
    private final ConcurrentHashMap<UUID, UniResponser> pendingUniResponses = new ConcurrentHashMap<>();

    public void onMessage(RcpMessage message) {
        var handler = handlerRegistry.getHandler(message.getClass());
        if (handler == null) {
            throw new IllegalArgumentException("No handler registered for " + message.getClass());
        }
        var response = handler.handle(message);
        response.setCorrelationId(message.getId());

        var uniResponder = pendingUniResponses.remove(message.getId());
        if (uniResponder != null) {
            uniResponder.respond(response);
        }
    }

    public Uni<RcpMessage> sendMessageAndGetResponse(RcpMessage request) {
        return Uni.createFrom().emitter(emitter -> {
            var id = UUID.randomUUID();
            request.setId(id);
            var uniResponder = new UniResponser(emitter);
            pendingUniResponses.put(id, uniResponder);
        });
    }
}
