package bot.ironclad.protocol;

import java.util.UUID;

public abstract class RcpMessage {
    private UUID id;
    private UUID correlationId;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(UUID correlationId) {
        this.correlationId = correlationId;
    }
}
