package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CrossChannelDedupTest {

    @Test
    void smsAndEmailForSameTransactionBecomeOneTransaction() {

        RawMessage sms = new RawMessage(
                "m-sms-1",
                "sms",
                "AD-HDFCBK-S",
                OffsetDateTime.now(),
                "dev-1",
                """
                Rs.89.01 debited from a/c **4821 on 05-07-26 at 11:03 to PVR CINEMAS. Avl Bal: Rs.1000.00
                """);

        RawMessage email = new RawMessage(
                "m-email-1",
                "email",
                "alerts@hdfcbank.net",
                OffsetDateTime.now(),
                "dev-1",
                """
                Date: Sun, 05 Jul 2026 11:03:00 +0530
                Subject: Transaction alert on your account

                Your account ending 4821 has been debited with INR 89.01.
                Merchant / Remarks: PVR CINEMAS
                Transaction reference: 2281088873
                """);

        InMemoryLedgerStore store = new InMemoryLedgerStore();

        new IngestService(new Parsers(), store)
                .ingestMessages(java.util.List.of(sms, email));

        assertEquals(1, store.count());

        assertEquals(
                2,
                store.all().get(0).sourceMessageIds().size()
        );
    }
}