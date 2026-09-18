package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.IciciSmsParser;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class IciciSmsParserTest {
    private final IciciSmsParser parser = new IciciSmsParser();

    @Test
    void parsesV2CreditTransaction() {
        RawMessage message = new RawMessage(
                "m-00161-5b493e",
                "sms",
                "VM-ICICIB-T",
                OffsetDateTime.parse("2026-07-23T16:52:00+05:30"),
                "dev-34aed0f15820",
                "ICICI Bank Acct XX9075 Cr INR 1250.33 on 23-Jul-2026 16:52; "
                        + "INTEREST CREDIT ref no 424353460512. BalAvl Rs 52,846.30"
        );

        Optional<ParsedTxn> result = parser.parse(message);

        assertNotNull(result.orElse(null));

        ParsedTxn txn = result.get();

        assertEquals("9075", txn.accountLast4());
        assertEquals(Direction.CREDIT, txn.direction());
        assertEquals("1250.33", txn.amount().toPlainString());
        assertEquals(
                OffsetDateTime.parse("2026-07-23T16:52:00+05:30"),
                txn.occurredAt()
        );
        assertEquals("INTEREST CREDIT", txn.merchant());
        assertEquals("52846.30", txn.statedBalance().toPlainString());
    }

    @Test
    void parsesV2DebitWithWholeRupeeAmount() {
        RawMessage message = new RawMessage(
                "m-00162-9709eb",
                "sms",
                "VM-ICICIB-T",
                OffsetDateTime.parse("2026-07-23T18:41:00+05:30"),
                "dev-34aed0f15820",
                "ICICI Bank Acct XX9075 Dr INR 5 on 23-Jul-2026 18:41; "
                        + "UPI/BARBER ref no 154245459403. BalAvl Rs 52,841.30"
        );

        Optional<ParsedTxn> result = parser.parse(message);

        assertNotNull(result.orElse(null));

        ParsedTxn txn = result.get();

        assertEquals("9075", txn.accountLast4());
        assertEquals(Direction.DEBIT, txn.direction());
        assertEquals("5.00", txn.amount().toPlainString());
        assertEquals("UPI/BARBER", txn.merchant());
        assertEquals("52841.30", txn.statedBalance().toPlainString());
    }

    @Test
    void parsesV2DebitWithDecimalAmount() {
        RawMessage message = new RawMessage(
                "m-00163-bd2b21",
                "sms",
                "VM-ICICIB-T",
                OffsetDateTime.parse("2026-07-23T21:41:00+05:30"),
                "dev-34aed0f15820",
                "ICICI Bank Acct XX9075 Dr INR 10.00 on 23-Jul-2026 21:41; "
                        + "UPI/MILK BOOTH ref no 329658902748. BalAvl Rs 52,831.30"
        );

        Optional<ParsedTxn> result = parser.parse(message);

        assertNotNull(result.orElse(null));

        ParsedTxn txn = result.get();

        assertEquals(Direction.DEBIT, txn.direction());
        assertEquals("10.00", txn.amount().toPlainString());
        assertEquals("UPI/MILK BOOTH", txn.merchant());
    }
}
