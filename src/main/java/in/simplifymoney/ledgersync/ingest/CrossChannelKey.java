package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.model.Direction;

import java.math.BigDecimal;
import java.time.Instant;

public record CrossChannelKey(
        String accountLast4,
        Instant occurredAt,
        Direction direction,
        BigDecimal amount,
        String merchant
) {
}
