package bot.ironclad;

@FunctionalInterface
public interface MessageHandler<TReq extends RcpRequest<TRes>, TRes extends RcpMessage> {
    TRes handle(TReq request);
}
