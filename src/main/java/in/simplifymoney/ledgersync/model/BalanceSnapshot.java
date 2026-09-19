package in.simplifymoney.ledgersync.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * A stated bank balance observation at a specific point in time.
 * Can originate from a transaction notification (which quotes an available balance)
 * or a standalone balance-alert SMS message.
 */
public record BalanceSnapshot(
        String accountLast4,
        OffsetDateTime observedAt,
        BigDecimal balance,
        String sourceMessageId) {

    public BalanceSnapshot {
        Objects.requireNonNull(accountLast4, "accountLast4");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(balance, "balance");
        Objects.requireNonNull(sourceMessageId, "sourceMessageId");

        if (accountLast4.length() != 4 || !accountLast4.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("accountLast4 must be 4 digits: " + accountLast4);
        }
        balance = balance.setScale(2);
    }
}
