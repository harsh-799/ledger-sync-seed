package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.model.Direction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record TxnKey(
        String accountLast4,
        OffsetDateTime occurredAt,
        Direction direction,
        BigDecimal amount,
        String merchant,
        BigDecimal statedBalance
) {
}
