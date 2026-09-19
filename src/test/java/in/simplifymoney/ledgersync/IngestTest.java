package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IngestTest {

    @Test
    void duplicateMessagesBecomeOneTransactionWithBothSourceIds() throws Exception {
        RawMessage first = new RawMessage(
                "m-1",
                "sms",
                "AD-HDFCBK-S",
                OffsetDateTime.parse("2026-07-04T12:25:00+05:30"),
                "dev-1",
                "Rs 154.49 debited from a/c **4821 on 04-07-26 at 12:24 "
                        + "to RELIANCE SMART. Avl Bal: Rs.92,058.61.");

        RawMessage duplicate = new RawMessage(
                "m-2",
                "sms",
                "AD-HDFCBK-S",
                OffsetDateTime.parse("2026-07-04T13:09:00+05:30"),
                "dev-1",
                "Rs 154.49 debited from a/c **4821 on 04-07-26 at 12:24 "
                        + "to RELIANCE SMART. Avl Bal: Rs.92,058.61.");

        InMemoryLedgerStore store = new InMemoryLedgerStore();
        IngestService ingest = new IngestService(new Parsers(), store);

        ingest.ingestMessages(List.of(first, duplicate));

        assertEquals(1, store.count());

        NormalizedTxn txn = store.all().get(0);

        assertEquals(
                List.of("m-1", "m-2"),
                txn.sourceMessageIds()
        );
    }

    @Test
    void repeatedIngestionIsIdempotentAndDoesNotDuplicateTransactions() throws Exception {
        RawMessage msg = new RawMessage(
                "m-1",
                "sms",
                "AD-HDFCBK-S",
                OffsetDateTime.parse("2026-07-04T12:25:00+05:30"),
                "dev-1",
                "Rs 154.49 debited from a/c **4821 on 04-07-26 at 12:24 "
                        + "to RELIANCE SMART. Avl Bal: Rs.92,058.61.");

        InMemoryLedgerStore store = new InMemoryLedgerStore();
        IngestService ingest = new IngestService(new Parsers(), store);

        ingest.ingestMessages(List.of(msg));
        assertEquals(1, store.count());
        assertEquals(1, store.balanceSnapshots().size());

        // Ingest the same message again in a subsequent run
        ingest.ingestMessages(List.of(msg));
        assertEquals(1, store.count());
        assertEquals(1, store.balanceSnapshots().size());
    }
}
