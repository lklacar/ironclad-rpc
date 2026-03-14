package bot.ironclad;

import io.smallrye.mutiny.Multi;

@FunctionalInterface
public interface StreamMessageHandler<TReq extends RcpStreamRequest<TRes>, TRes extends RcpMessage> {
    Multi<TRes> handle(TReq request);
}
