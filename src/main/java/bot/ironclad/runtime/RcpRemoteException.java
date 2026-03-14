package bot.ironclad.runtime;

import bot.ironclad.protocol.RcpErrorResponse;

public final class RcpRemoteException extends RuntimeException {
    private final String errorType;
    private final String detail;

    public RcpRemoteException(RcpErrorResponse errorResponse) {
        super("%s: %s".formatted(errorResponse.getErrorType(), errorResponse.getDetail()));
        this.errorType = errorResponse.getErrorType();
        this.detail = errorResponse.getDetail();
    }

    public String getErrorType() {
        return errorType;
    }

    public String getDetail() {
        return detail;
    }
}
