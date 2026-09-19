package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.model.BalanceSnapshot;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.report.Reports;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconciliationTest {

    @Test
    void fullyReconcilingAccountProducesNoDiscrepancies() {
        OffsetDateTime t0 = OffsetDateTime.parse("2026-07-01T10:00:00+05:30");
        OffsetDateTime t1 = OffsetDateTime.parse("2026-07-01T12:00:00+05:30");
        OffsetDateTime t2 = OffsetDateTime.parse("2026-07-01T15:00:00+05:30");

        List<BalanceSnapshot> snapshots = List.of(
                new BalanceSnapshot("9075", t0, new BigDecimal("1000.00"), "msg-0"),
                new BalanceSnapshot("9075", t1, new BigDecimal("800.00"), "msg-1"),
                new BalanceSnapshot("9075", t2, new BigDecimal("1300.00"), "msg-2")
        );

        List<NormalizedTxn> ledger = List.of(
                new NormalizedTxn("9075", t1, Direction.DEBIT, new BigDecimal("200.00"),
                        Category.SPEND, "STORE", List.of("msg-1")),
                new NormalizedTxn("9075", t2, Direction.CREDIT, new BigDecimal("500.00"),
                        Category.INCOME, "SALARY", List.of("msg-2"))
        );

        Map<String, Object> report = Reports.reconciliation(ledger, snapshots);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> discrepancies =
                (List<Map<String, Object>>) report.get("discrepancies");

        assertTrue(discrepancies.isEmpty(), "Expected no discrepancies for fully reconciled account");
    }

    @Test
    void syntheticDiscrepancyDetectedWhenBalanceDropsUnexplained() {
        OffsetDateTime t0 = OffsetDateTime.parse("2026-07-10T09:00:00+05:30");
        OffsetDateTime t1 = OffsetDateTime.parse("2026-07-10T14:30:00+05:30");

        List<BalanceSnapshot> snapshots = List.of(
                new BalanceSnapshot("4821", t0, new BigDecimal("5000.00"), "msg-10"),
                new BalanceSnapshot("4821", t1, new BigDecimal("4450.00"), "msg-11")
        );

        // Ledger only has a 50.00 debit; expected balance is 4950.00, but stated is 4450.00 (gap of 500.00)
        List<NormalizedTxn> ledger = List.of(
                new NormalizedTxn("4821", t1, Direction.DEBIT, new BigDecimal("50.00"),
                        Category.SPEND, "CAFE", List.of("msg-11"))
        );

        Map<String, Object> report = Reports.reconciliation(ledger, snapshots);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> discrepancies =
                (List<Map<String, Object>>) report.get("discrepancies");

        assertEquals(1, discrepancies.size());

        Map<String, Object> d = discrepancies.get(0);
        assertEquals("4821", d.get("account_last4"));
        assertEquals("500.00", d.get("amount"));
        assertEquals(t1.toString(), d.get("occurred_at"));
        assertTrue(d.get("note").toString().contains("500.00"));
    }

    @Test
    void realCorpusReconciliationShowsDiscrepancyFor4821AndNoneFor9075() throws Exception {
        InMemoryLedgerStore store = new InMemoryLedgerStore();
        IngestService ingest = new IngestService(new Parsers(), store);
        ingest.ingestFile(Path.of("fixtures/corpus-a.jsonl"));

        List<NormalizedTxn> ledger = store.all();
        List<BalanceSnapshot> snapshots = store.balanceSnapshots();

        Map<String, Object> report = Reports.reconciliation(ledger, snapshots);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> discrepancies =
                (List<Map<String, Object>>) report.get("discrepancies");

        List<Map<String, Object>> for4821 = discrepancies.stream()
                .filter(d -> "4821".equals(d.get("account_last4")))
                .toList();

        List<Map<String, Object>> for9075 = discrepancies.stream()
                .filter(d -> "9075".equals(d.get("account_last4")))
                .toList();

        // Account 9075 reconciles completely with no unexplained movement
        assertTrue(for9075.isEmpty(), "Account 9075 should have 0 discrepancies");

        // Account 4821 has exactly one unexplained drop of ₹7,500 on 2026-07-29
        assertEquals(1, for4821.size(), "Account 4821 should have exactly 1 discrepancy");

        Map<String, Object> d = for4821.get(0);
        assertEquals("4821", d.get("account_last4"));
        assertEquals("7500.00", d.get("amount"));
        assertTrue(d.get("occurred_at").toString().startsWith("2026-07-29T17:06"));
    }
}
