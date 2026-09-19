package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.Backfill;
import in.simplifymoney.ledgersync.store.ConsistencyChecker;
import in.simplifymoney.ledgersync.store.DocumentStore;
import in.simplifymoney.ledgersync.store.InMemoryDocumentStore;
import in.simplifymoney.ledgersync.store.SqlLedgerStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsistencyCheckerTest {

    private static NormalizedTxn txn(
            String accountLast4,
            String occurredAt,
            Direction direction,
            String amount,
            Category category,
            String merchant,
            List<String> sourceMessageIds) {
        return new NormalizedTxn(
                accountLast4,
                OffsetDateTime.parse(occurredAt),
                direction,
                new BigDecimal(amount),
                category,
                merchant,
                sourceMessageIds
        );
    }

    @Test
    @DisplayName("consistent stores produce 0 divergences")
    void consistentStoresProduceNoDivergences(@TempDir Path tempDir) throws Exception {
        try (SqlLedgerStore sql = new SqlLedgerStore(tempDir.resolve("consistent_db"))) {
            sql.migrate(Path.of("db", "migration"));

            DocumentStore docs = new InMemoryDocumentStore();
            Backfill backfill = new Backfill(sql, docs);
            backfill.run();

            ConsistencyChecker checker = new ConsistencyChecker(sql, docs);
            List<ConsistencyChecker.Divergence> divergences = checker.check();

            assertTrue(divergences.isEmpty(), "Expected 0 divergences for properly backfilled store, got: " + divergences);
        }
    }

    @Test
    @DisplayName("detects transaction missing from DocumentStore")
    void detectsMissingTransactionInDocumentStore(@TempDir Path tempDir) throws Exception {
        try (SqlLedgerStore sql = new SqlLedgerStore(tempDir.resolve("missing_db"))) {
            Path emptyDir = tempDir.resolve("migrations");
            java.nio.file.Files.createDirectories(emptyDir);
            java.nio.file.Files.copy(Path.of("db", "migration", "V1__initial.sql"), emptyDir.resolve("V1__initial.sql"));
            sql.migrate(emptyDir);

            NormalizedTxn t1 = txn("4821", "2026-07-01T10:00:00+05:30", Direction.DEBIT,
                    "100.00", Category.SPEND, "STORE1", List.of("m-1"));
            NormalizedTxn t2 = txn("4821", "2026-07-02T10:00:00+05:30", Direction.DEBIT,
                    "200.00", Category.SPEND, "STORE2", List.of("m-2"));

            sql.save(t1);
            sql.save(t2);

            // DocumentStore only receives t1 (t2 is missing)
            DocumentStore docs = new InMemoryDocumentStore();
            docs.save(t1);

            ConsistencyChecker checker = new ConsistencyChecker(sql, docs);
            List<ConsistencyChecker.Divergence> divergences = checker.check();

            assertFalse(divergences.isEmpty());
            assertTrue(divergences.stream().anyMatch(d -> d.what().contains("missing_transaction_in_documents")));
            assertTrue(divergences.stream().anyMatch(d -> d.what().contains("by_message_id_missing:m-2")));
            assertTrue(divergences.stream().anyMatch(d -> d.what().contains("category_total_mismatch")));
        }
    }

    @Test
    @DisplayName("detects extra transaction injected into DocumentStore")
    void detectsExtraTransactionInDocumentStore(@TempDir Path tempDir) throws Exception {
        try (SqlLedgerStore sql = new SqlLedgerStore(tempDir.resolve("extra_db"))) {
            Path emptyDir = tempDir.resolve("migrations");
            java.nio.file.Files.createDirectories(emptyDir);
            java.nio.file.Files.copy(Path.of("db", "migration", "V1__initial.sql"), emptyDir.resolve("V1__initial.sql"));
            sql.migrate(emptyDir);

            NormalizedTxn t1 = txn("4821", "2026-07-01T10:00:00+05:30", Direction.DEBIT,
                    "100.00", Category.SPEND, "STORE1", List.of("m-1"));
            sql.save(t1);

            DocumentStore docs = new InMemoryDocumentStore();
            docs.save(t1);

            // Injected extra transaction
            NormalizedTxn phantom = txn("4821", "2026-07-02T10:00:00+05:30", Direction.DEBIT,
                    "999.00", Category.SPEND, "PHANTOM", List.of("m-phantom"));
            docs.save(phantom);

            ConsistencyChecker checker = new ConsistencyChecker(sql, docs);
            List<ConsistencyChecker.Divergence> divergences = checker.check();

            assertFalse(divergences.isEmpty());
            assertTrue(divergences.stream().anyMatch(d -> d.what().contains("extra_transaction_in_documents")));
            assertTrue(divergences.stream().anyMatch(d -> d.what().contains("category_total_mismatch")));
        }
    }

    @Test
    @DisplayName("detects deliberately altered amount in DocumentStore")
    void detectsAlteredAmount(@TempDir Path tempDir) throws Exception {
        try (SqlLedgerStore sql = new SqlLedgerStore(tempDir.resolve("altered_amt_db"))) {
            Path emptyDir = tempDir.resolve("migrations");
            java.nio.file.Files.createDirectories(emptyDir);
            java.nio.file.Files.copy(Path.of("db", "migration", "V1__initial.sql"), emptyDir.resolve("V1__initial.sql"));
            sql.migrate(emptyDir);

            NormalizedTxn original = txn("4821", "2026-07-01T10:00:00+05:30", Direction.DEBIT,
                    "500.00", Category.SPEND, "AMAZON", List.of("m-1"));
            sql.save(original);

            // DocumentStore has altered amount 50.00
            NormalizedTxn altered = txn("4821", "2026-07-01T10:00:00+05:30", Direction.DEBIT,
                    "50.00", Category.SPEND, "AMAZON", List.of("m-1"));
            DocumentStore docs = new InMemoryDocumentStore();
            docs.save(altered);

            ConsistencyChecker checker = new ConsistencyChecker(sql, docs);
            List<ConsistencyChecker.Divergence> divergences = checker.check();

            assertFalse(divergences.isEmpty());
            assertTrue(divergences.stream().anyMatch(d -> d.what().contains("amount_mismatch")));

            ConsistencyChecker.Divergence amtDivergence = divergences.stream()
                    .filter(d -> d.what().contains("amount_mismatch"))
                    .findFirst().orElseThrow();
            assertEquals("500.00", amtDivergence.inSql());
            assertEquals("50.00", amtDivergence.inDocuments());
        }
    }

    @Test
    @DisplayName("detects altered category in DocumentStore")
    void detectsAlteredCategory(@TempDir Path tempDir) throws Exception {
        try (SqlLedgerStore sql = new SqlLedgerStore(tempDir.resolve("altered_cat_db"))) {
            Path emptyDir = tempDir.resolve("migrations");
            java.nio.file.Files.createDirectories(emptyDir);
            java.nio.file.Files.copy(Path.of("db", "migration", "V1__initial.sql"), emptyDir.resolve("V1__initial.sql"));
            sql.migrate(emptyDir);

            NormalizedTxn original = txn("4821", "2026-07-01T10:00:00+05:30", Direction.DEBIT,
                    "20.00", Category.MICRO, "UPI/TEA", List.of("m-1"));
            sql.save(original);

            // DocumentStore has SPEND instead of MICRO
            NormalizedTxn altered = txn("4821", "2026-07-01T10:00:00+05:30", Direction.DEBIT,
                    "20.00", Category.SPEND, "UPI/TEA", List.of("m-1"));
            DocumentStore docs = new InMemoryDocumentStore();
            docs.save(altered);

            ConsistencyChecker checker = new ConsistencyChecker(sql, docs);
            List<ConsistencyChecker.Divergence> divergences = checker.check();

            assertFalse(divergences.isEmpty());
            assertTrue(divergences.stream().anyMatch(d -> d.what().contains("category_mismatch")));

            ConsistencyChecker.Divergence catDivergence = divergences.stream()
                    .filter(d -> d.what().contains("category_mismatch"))
                    .findFirst().orElseThrow();
            assertEquals("MICRO", catDivergence.inSql());
            assertEquals("SPEND", catDivergence.inDocuments());
        }
    }

    @Test
    @DisplayName("detects altered merchant in DocumentStore")
    void detectsAlteredMerchant(@TempDir Path tempDir) throws Exception {
        try (SqlLedgerStore sql = new SqlLedgerStore(tempDir.resolve("altered_merchant_db"))) {
            Path emptyDir = tempDir.resolve("migrations");
            java.nio.file.Files.createDirectories(emptyDir);
            java.nio.file.Files.copy(Path.of("db", "migration", "V1__initial.sql"), emptyDir.resolve("V1__initial.sql"));
            sql.migrate(emptyDir);

            NormalizedTxn original = txn("4821", "2026-07-01T10:00:00+05:30", Direction.DEBIT,
                    "150.00", Category.SPEND, "AMAZON", List.of("m-1"));
            sql.save(original);

            NormalizedTxn altered = txn("4821", "2026-07-01T10:00:00+05:30", Direction.DEBIT,
                    "150.00", Category.SPEND, "FLIPKART", List.of("m-1"));
            DocumentStore docs = new InMemoryDocumentStore();
            docs.save(altered);

            ConsistencyChecker checker = new ConsistencyChecker(sql, docs);
            List<ConsistencyChecker.Divergence> divergences = checker.check();

            assertFalse(divergences.isEmpty());
            assertTrue(divergences.stream().anyMatch(d -> d.what().contains("merchant_mismatch")));
        }
    }

    @Test
    @DisplayName("detects missing source message ID in DocumentStore")
    void detectsMissingSourceMessageId(@TempDir Path tempDir) throws Exception {
        try (SqlLedgerStore sql = new SqlLedgerStore(tempDir.resolve("altered_msg_db"))) {
            Path emptyDir = tempDir.resolve("migrations");
            java.nio.file.Files.createDirectories(emptyDir);
            java.nio.file.Files.copy(Path.of("db", "migration", "V1__initial.sql"), emptyDir.resolve("V1__initial.sql"));
            sql.migrate(emptyDir);

            NormalizedTxn original = txn("4821", "2026-07-01T10:00:00+05:30", Direction.DEBIT,
                    "150.00", Category.SPEND, "AMAZON", List.of("m-sms", "m-email"));
            sql.save(original);

            // DocumentStore only has "m-sms"
            NormalizedTxn altered = txn("4821", "2026-07-01T10:00:00+05:30", Direction.DEBIT,
                    "150.00", Category.SPEND, "AMAZON", List.of("m-sms"));
            DocumentStore docs = new InMemoryDocumentStore();
            docs.save(altered);

            ConsistencyChecker checker = new ConsistencyChecker(sql, docs);
            List<ConsistencyChecker.Divergence> divergences = checker.check();

            assertFalse(divergences.isEmpty());
            assertTrue(divergences.stream().anyMatch(d -> d.what().contains("source_message_ids_mismatch")
                    || d.what().contains("by_message_id_missing:m-email")));
        }
    }
}
