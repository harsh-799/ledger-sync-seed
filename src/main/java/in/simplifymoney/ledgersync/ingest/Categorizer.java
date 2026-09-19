package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;

import java.math.BigDecimal;

/**
 * Centralized classification logic for transaction categories.
 *
 * Exactly four categories:
 * - TRANSFER: Movement between user's own accounts (designated by user name "PARAG KAPOOR").
 * - MICRO:    Qualifying small UPI debits of Rs. 100.00 or less.
 * - SPEND:    Normal outgoing spending (debits not categorized as TRANSFER or MICRO).
 * - INCOME:   Normal incoming money (credits not categorized as TRANSFER).
 */
public final class Categorizer {

    public static final BigDecimal MICRO_THRESHOLD = new BigDecimal("100.00");
    public static final String USER_NAME = "PARAG KAPOOR";

    private Categorizer() {}

    /**
     * Categorizes a transaction into exactly one of: SPEND, INCOME, MICRO, TRANSFER.
     *
     * @param direction DEBIT or CREDIT
     * @param amount    the transaction amount
     * @param merchant  the merchant or transaction remarks
     * @return the Category
     */
    public static Category categorize(Direction direction, BigDecimal amount, String merchant) {
        if (isTransfer(merchant)) {
            return Category.TRANSFER;
        }
        if (direction == Direction.DEBIT) {
            if (isMicro(direction, amount, merchant)) {
                return Category.MICRO;
            }
            return Category.SPEND;
        }
        return Category.INCOME;
    }

    /**
     * A transfer is a movement between the user's own accounts.
     * In this corpus, all legs between account 4821 and 9075 are designated
     * with the user's name: "PARAG KAPOOR" (e.g. "IMPS/P2A/PARAG KAPOOR").
     */
    public static boolean isTransfer(String merchant) {
        if (merchant == null) {
            return false;
        }
        return merchant.toUpperCase().contains(USER_NAME);
    }

    /**
     * A MICRO transaction is a qualifying UPI debit of Rs. 100.00 or less.
     * Small non-UPI debits (e.g., card or merchant charges <= 100) are SPEND.
     */
    public static boolean isMicro(Direction direction, BigDecimal amount, String merchant) {
        if (direction != Direction.DEBIT || amount == null || merchant == null) {
            return false;
        }
        return amount.compareTo(MICRO_THRESHOLD) <= 0
                && merchant.toUpperCase().contains("UPI");
    }

    /**
     * Overload for checking if an amount and merchant qualify for MICRO debits.
     */
    public static boolean isMicro(BigDecimal amount, String merchant) {
        return isMicro(Direction.DEBIT, amount, merchant);
    }
}
