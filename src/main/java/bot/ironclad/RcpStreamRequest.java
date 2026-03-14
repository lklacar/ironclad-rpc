package bot.ironclad;

public abstract class RcpStreamRequest<TResponse extends RcpMessage> extends RcpRequest<TResponse> {
    protected RcpStreamRequest(Class<TResponse> responseType) {
        super(responseType);
    }
}
