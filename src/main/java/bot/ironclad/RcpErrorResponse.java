package bot.ironclad;

import java.util.Objects;

public final class RcpErrorResponse extends RcpMessage {
    private final String errorType;
    private final String detail;

    public RcpErrorResponse(String errorType, String detail) {
        this.errorType = Objects.requireNonNull(errorType, "errorType");
        this.detail = detail == null || detail.isBlank() ? errorType : detail;
    }

    public static RcpErrorResponse from(Throwable throwable) {
        Objects.requireNonNull(throwable, "throwable");
        return new RcpErrorResponse(throwable.getClass().getName(), throwable.getMessage());
    }

    public String getErrorType() {
        return errorType;
    }

    public String getDetail() {
        return detail;
    }
}
