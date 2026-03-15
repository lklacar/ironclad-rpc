package bot.ironclad.handler;

import bot.ironclad.protocol.RcpMessage;
import bot.ironclad.protocol.RcpRequest;
import io.smallrye.mutiny.Uni;

@FunctionalInterface
public interface MessageHandler<TReq extends RcpRequest<TRes>, TRes extends RcpMessage> {
    Uni<TRes> handle(TReq request);
}
