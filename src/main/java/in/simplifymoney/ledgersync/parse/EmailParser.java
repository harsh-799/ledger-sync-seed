package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank transaction alert emails.
 *
 * Not written yet. The corpus contains them and they are currently all dropped.
 */
public final class EmailParser implements MessageParser {

    private static final Pattern TRANSACTION = Pattern.compile(
            "account ending (?<acct>\\d{4}) has been "
                    + "(?<dir>debited|credited) with "
                    + "(?:INR|Rs\\.?)\\s*"
                    + "(?<amount>[0-9,]+(?:\\.[0-9]{2})?)\\."
                    + ".*?Merchant / Remarks: (?<merchant>.+?)\\n",
            Pattern.DOTALL);

    private static final Pattern DATE = Pattern.compile(
            "Date: .*?, (?<when>\\d{2} \\w{3} \\d{4} "
                    + "\\d{2}:\\d{2}:\\d{2} [+-]\\d{4})");

    private static final Pattern TRANSACTION_REFERENCE =
            Pattern.compile("Transaction reference:\\s*(?<ref>\\d+)");

    @Override
    public boolean supports(RawMessage m) {
        return "email".equals(m.channel());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        String body = m.body();

        Matcher transaction = TRANSACTION.matcher(body);
        if (!transaction.find()) {
            return Optional.empty();
        }

        Matcher date = DATE.matcher(body);
        if (!date.find()) {
            return Optional.empty();
        }

        String acct = transaction.group("acct");

        Direction direction = "debited".equals(transaction.group("dir"))
                ? Direction.DEBIT
                : Direction.CREDIT;

        BigDecimal amount = new BigDecimal(
                transaction.group("amount").replace(",", "")
        ).setScale(2);

        OffsetDateTime occurredAt = OffsetDateTime.parse(
                date.group("when"),
                java.time.format.DateTimeFormatter.ofPattern(
                        "dd MMM yyyy HH:mm:ss xx"
                )
        );

        String merchant = transaction.group("merchant").trim();

        Matcher refMatcher = TRANSACTION_REFERENCE.matcher(body);

        if (!refMatcher.find()) {
            return Optional.empty();
        }

        String reference = refMatcher.group("ref");

        return Optional.of(new ParsedTxn(
                acct,
                occurredAt,
                direction,
                amount,
                merchant,
                null,
                reference,
                m.messageId()
        ));
    }
}
