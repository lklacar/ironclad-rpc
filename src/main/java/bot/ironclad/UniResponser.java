package bot.ironclad;

import io.smallrye.mutiny.subscription.UniEmitter;

public record UniResponser(
        UniEmitter<? super RcpMessage> emitter
) {

    public void respond(RcpMessage response) {
        emitter.complete(response);
    }
}
