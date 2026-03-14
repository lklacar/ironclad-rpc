package bot.ironclad.handler;

import bot.ironclad.protocol.RcpMessage;
import bot.ironclad.protocol.RcpRequest;

@FunctionalInterface
public interface MessageHandler<TReq extends RcpRequest<TRes>, TRes extends RcpMessage> {
    TRes handle(TReq request);
}
