package bot.ironclad.protocol;

import java.util.Objects;

public abstract class RcpRequest<TResponse extends RcpMessage> extends RcpMessage {
    private final Class<TResponse> responseType;

    protected RcpRequest(Class<TResponse> responseType) {
        this.responseType = Objects.requireNonNull(responseType, "responseType");
    }

    public final Class<TResponse> getResponseType() {
        return responseType;
    }
}
