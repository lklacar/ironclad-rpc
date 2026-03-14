package bot.ironclad.handler;

import bot.ironclad.protocol.RcpMessage;
import bot.ironclad.protocol.RcpStreamRequest;
import io.smallrye.mutiny.Multi;

@FunctionalInterface
public interface StreamMessageHandler<TReq extends RcpStreamRequest<TRes>, TRes extends RcpMessage> {
    Multi<TRes> handle(TReq request);
}
