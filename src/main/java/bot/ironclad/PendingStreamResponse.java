package bot.ironclad;

import io.smallrye.mutiny.subscription.MultiEmitter;

import java.util.Objects;
import java.util.UUID;

final class PendingStreamResponse<TResponse extends RcpMessage> extends PendingResponse<TResponse> {
    private final MultiEmitter<? super TResponse> emitter;

    PendingStreamResponse(UUID connectionId, Class<TResponse> responseType, MultiEmitter<? super TResponse> emitter) {
        super(connectionId, responseType);
        this.emitter = Objects.requireNonNull(emitter, "emitter");
    }

    @Override
    boolean accept(RcpMessage message) {
        if (message instanceof RcpStreamCompleted) {
            emitter.complete();
            return true;
        }

        try {
            emitter.emit(requireResponse(message));
            return false;
        } catch (Throwable throwable) {
            emitter.fail(throwable);
            return true;
        }
    }

    @Override
    void fail(Throwable throwable) {
        emitter.fail(throwable);
    }
}
