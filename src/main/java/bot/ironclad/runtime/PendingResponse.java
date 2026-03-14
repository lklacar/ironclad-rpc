package bot.ironclad.runtime;

import bot.ironclad.protocol.RcpErrorResponse;
import bot.ironclad.protocol.RcpMessage;

import java.util.Objects;
import java.util.UUID;

abstract sealed class PendingResponse<TResponse extends RcpMessage>
        permits PendingSingleResponse, PendingStreamResponse {
    private final UUID connectionId;
    private final Class<TResponse> responseType;

    PendingResponse(UUID connectionId, Class<TResponse> responseType) {
        this.connectionId = Objects.requireNonNull(connectionId, "connectionId");
        this.responseType = Objects.requireNonNull(responseType, "responseType");
    }

    UUID connectionId() {
        return connectionId;
    }

    protected final TResponse requireResponse(RcpMessage message) {
        if (message instanceof RcpErrorResponse errorResponse) {
            throw new RcpRemoteException(errorResponse);
        }

        if (!responseType.isInstance(message)) {
            throw new IllegalArgumentException(
                    "Expected response type %s but received %s".formatted(
                            responseType.getName(),
                            message.getClass().getName()
                    )
            );
        }

        return responseType.cast(message);
    }

    abstract boolean accept(RcpMessage message);

    abstract void fail(Throwable throwable);
}
