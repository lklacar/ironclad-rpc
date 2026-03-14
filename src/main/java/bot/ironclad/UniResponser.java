package bot.ironclad;

import io.smallrye.mutiny.subscription.UniEmitter;

public record UniResponser<T extends RcpMessage>(
        UniEmitter<? super T> emitter
) {

    public void respond(T response) {
        emitter.complete(response);
    }
}
