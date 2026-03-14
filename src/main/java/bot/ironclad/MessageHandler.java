package bot.ironclad;

public interface MessageHandler<TReq extends RcpMessage, TRes extends RcpMessage> {
    TRes handle(TReq request);
}
