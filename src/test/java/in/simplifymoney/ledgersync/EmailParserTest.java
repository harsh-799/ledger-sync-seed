package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.EmailParser;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailParserTest {

    @Test
    void parsesDebitTransactionEmail() {
        RawMessage message = new RawMessage(
                "m-email-1",
                "email",
                "alerts@hdfcbank.net",
                OffsetDateTime.parse("2026-07-04T13:09:00+05:30"),
                "dev-1",
                """
                Date: Sat, 04 Jul 2026 12:24:00 +0530
                Subject: Transaction alert on your account

                Dear Customer,

                Your account ending 4821 has been debited with INR 154.49.
                Merchant / Remarks: RELIANCE SMART
                Transaction reference: 7827698558

                This is a system generated email.
                """);

        EmailParser parser = new EmailParser();

        Optional<ParsedTxn> result = parser.parse(message);

        assertTrue(result.isPresent());

        ParsedTxn txn = result.get();

        assertEquals("4821", txn.accountLast4());
        assertEquals(Direction.DEBIT, txn.direction());
        assertEquals("154.49", txn.amount().toPlainString());
        assertEquals(
                OffsetDateTime.parse("2026-07-04T12:24:00+05:30"),
                txn.occurredAt());
        assertEquals("RELIANCE SMART", txn.merchant());
        assertEquals("m-email-1", txn.sourceMessageId());
        assertEquals("7827698558", txn.transactionReference());
    }

    @Test
    void duplicateEmailsBecomeOneTransactionWithBothSourceIds() {
        RawMessage first = new RawMessage(
                "m-email-1",
                "email",
                "alerts@hdfcbank.net",
                OffsetDateTime.parse("2026-07-05T14:03:00+05:30"),
                "dev-1",
                """
                Date: Sun, 05 Jul 2026 11:03:00 +0530
                Subject: Transaction alert on your account
    
                Dear Customer,
    
                Your account ending 4821 has been debited with INR 89.01.
                Merchant / Remarks: PVR CINEMAS
                Transaction reference: 2281088873
    
                This is a system generated email.
                """);

        RawMessage duplicate = new RawMessage(
                "m-email-2",
                "email",
                "alerts@hdfcbank.net",
                OffsetDateTime.parse("2026-07-05T15:10:00+05:30"),
                "dev-1",
                """
                Date: Sun, 05 Jul 2026 11:03:00 +0530
                Subject: Transaction alert on your account
    
                Dear Customer,
    
                Your account ending 4821 has been debited with INR 89.01.
                Merchant / Remarks: PVR CINEMAS ONLINE
                Transaction reference: 2281088873
    
                This is a system generated email.
                """);

        InMemoryLedgerStore store = new InMemoryLedgerStore();
        IngestService ingest = new IngestService(new Parsers(), store);

        ingest.ingestMessages(List.of(first, duplicate));

        assertEquals(1, store.count());

        NormalizedTxn txn = store.all().get(0);

        assertEquals(
                List.of("m-email-1", "m-email-2"),
                txn.sourceMessageIds()
        );
    }
}
