package bot.ironclad.interceptor;

import bot.ironclad.connection.RcpConnection;
import bot.ironclad.protocol.RcpMessage;
import bot.ironclad.protocol.RcpRequest;
import bot.ironclad.protocol.RcpStreamRequest;
import io.smallrye.mutiny.Multi;

import java.util.UUID;
import java.util.function.Function;

public interface RcpServerInterceptor<T extends RcpConnection> {
    default <TReq extends RcpRequest<TRes>, TRes extends RcpMessage> TRes interceptUnary(
            T connection,
            UUID connectionId,
            TReq request,
            Function<TReq, TRes> next
    ) {
        return next.apply(request);
    }

    default <TReq extends RcpStreamRequest<TRes>, TRes extends RcpMessage> Multi<TRes> interceptStream(
            T connection,
            UUID connectionId,
            TReq request,
            Function<TReq, Multi<TRes>> next
    ) {
        return next.apply(request);
    }
}
