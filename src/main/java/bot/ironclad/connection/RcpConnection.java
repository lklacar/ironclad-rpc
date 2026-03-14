package bot.ironclad.connection;

import bot.ironclad.protocol.RcpMessage;

public interface RcpConnection {
    void send(RcpMessage message);
}
