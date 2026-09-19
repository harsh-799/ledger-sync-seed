package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.model.Direction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record TxnKey(
        String accountLast4,
        String transactionReference,
        OffsetDateTime occurredAt,
        Direction direction,
        BigDecimal amount,
        String merchant,
        BigDecimal statedBalance
) {

    public static TxnKey forReference(
            String accountLast4,
            String transactionReference) {

        return new TxnKey(
                accountLast4,
                transactionReference,
                null,
                null,
                null,
                null,
                null
        );
    }

    public static TxnKey forDetails(
            String accountLast4,
            OffsetDateTime occurredAt,
            Direction direction,
            BigDecimal amount,
            String merchant,
            BigDecimal statedBalance) {

        return new TxnKey(
                accountLast4,
                null,
                occurredAt,
                direction,
                amount,
                merchant,
                statedBalance
        );
    }
}