package bot.ironclad;

import io.smallrye.mutiny.subscription.UniEmitter;

import java.util.Objects;
import java.util.UUID;

final class PendingSingleResponse<TResponse extends RcpMessage> extends PendingResponse<TResponse> {
    private final UniEmitter<? super TResponse> emitter;

    PendingSingleResponse(UUID connectionId, Class<TResponse> responseType, UniEmitter<? super TResponse> emitter) {
        super(connectionId, responseType);
        this.emitter = Objects.requireNonNull(emitter, "emitter");
    }

    @Override
    boolean accept(RcpMessage message) {
        try {
            emitter.complete(requireResponse(message));
        } catch (Throwable throwable) {
            emitter.fail(throwable);
        }
        return true;
    }

    @Override
    void fail(Throwable throwable) {
        emitter.fail(throwable);
    }
}
