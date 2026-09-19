package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.Backfill;
import in.simplifymoney.ledgersync.store.DocumentStore;
import in.simplifymoney.ledgersync.store.InMemoryDocumentStore;
import in.simplifymoney.ledgersync.store.SqlLedgerStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackfillTest {

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
    @DisplayName("migrates clean SQL rows to DocumentStore")
    void migratesCleanSqlRows(@TempDir Path tempDir) {
        try (SqlLedgerStore sql = new SqlLedgerStore(tempDir.resolve("clean_db"))) {
            sql.migrate(Path.of("db", "migration"));

            // Clear legacy seed data for an isolated test
            DocumentStore target = new InMemoryDocumentStore();

            NormalizedTxn t1 = txn("4821", "2026-07-04T12:00:00+05:30", Direction.DEBIT,
                    "150.00", Category.SPEND, "AMAZON", List.of("m-1"));
            NormalizedTxn t2 = txn("4821", "2026-07-05T14:00:00+05:30", Direction.DEBIT,
                    "25.00", Category.MICRO, "UPI/TEA", List.of("m-2"));
            NormalizedTxn t3 = txn("9075", "2026-07-06T10:00:00+05:30", Direction.CREDIT,
                    "5000.00", Category.INCOME, "SALARY", List.of("m-3"));

            sql.save(t1);
            sql.save(t2);
            sql.save(t3);

            Backfill backfill = new Backfill(sql, target);
            Backfill.Result result = backfill.run();

            // SQL has 15 seed rows + 3 new rows = 18 rows
            // 15 seed rows consolidate to 10 + 3 new rows = 13 written
            assertEquals(18, result.read());
            assertEquals(13, result.written());
            assertEquals(5, result.skipped());

            // Target has all 3 new transactions
            assertEquals(1, target.forAccountMonth("9075", YearMonth.of(2026, 7)).size());
            assertEquals(t3, target.forAccountMonth("9075", YearMonth.of(2026, 7)).get(0));
            assertTrue(target.byMessageId("m-1").isPresent());
            assertTrue(target.byMessageId("m-2").isPresent());
            assertTrue(target.byMessageId("m-3").isPresent());
        }
    }

    @Test
    @DisplayName("deduplicates dirty SQL rows and merges source message IDs")
    void deduplicatesDirtySqlRows(@TempDir Path tempDir) throws Exception {
        try (SqlLedgerStore sql = new SqlLedgerStore(tempDir.resolve("dirty_db"))) {
            // Setup schema without seed data
            Path emptyDir = tempDir.resolve("migrations");
            java.nio.file.Files.createDirectories(emptyDir);
            java.nio.file.Files.copy(Path.of("db", "migration", "V1__initial.sql"), emptyDir.resolve("V1__initial.sql"));
            sql.migrate(emptyDir);

            // 4 rows representing only 2 distinct transactions
            // Txn 1: duplicate rows with same message ID
            NormalizedTxn t1a = txn("4821", "2026-06-28T11:04:00+05:30", Direction.DEBIT,
                    "449.00", Category.SPEND, "SWIGGY", List.of("m-legacy-0001"));
            NormalizedTxn t1b = txn("4821", "2026-06-28T11:04:00+05:30", Direction.DEBIT,
                    "449.00", Category.SPEND, "SWIGGY", List.of("m-legacy-0001"));
            // Txn 1: duplicate row with different message ID
            NormalizedTxn t1c = txn("4821", "2026-06-28T11:04:00+05:30", Direction.DEBIT,
                    "449.00", Category.SPEND, "SWIGGY", List.of("m-legacy-0002"));

            // Txn 2: single row
            NormalizedTxn t2 = txn("9075", "2026-06-28T19:41:00+05:30", Direction.DEBIT,
                    "1299.50", Category.SPEND, "MYNTRA", List.of("m-legacy-0007"));

            sql.save(t1a);
            sql.save(t1b);
            sql.save(t1c);
            sql.save(t2);

            DocumentStore target = new InMemoryDocumentStore();
            Backfill backfill = new Backfill(sql, target);
            Backfill.Result result = backfill.run();

            assertEquals(4, result.read());
            assertEquals(2, result.written());
            assertEquals(2, result.skipped());

            // Check that Txn 1 merged both message IDs
            NormalizedTxn inTarget = target.forAccountMonth("4821", YearMonth.of(2026, 6)).get(0);
            assertEquals(List.of("m-legacy-0001", "m-legacy-0002"), inTarget.sourceMessageIds());

            // Both message IDs resolve to the same merged transaction
            assertTrue(target.byMessageId("m-legacy-0001").isPresent());
            assertTrue(target.byMessageId("m-legacy-0002").isPresent());
            assertEquals(inTarget, target.byMessageId("m-legacy-0001").get());
            assertEquals(inTarget, target.byMessageId("m-legacy-0002").get());
        }
    }

    @Test
    @DisplayName("idempotent when run multiple times on the same target")
    void idempotentWhenRunMultipleTimes(@TempDir Path tempDir) {
        try (SqlLedgerStore sql = new SqlLedgerStore(tempDir.resolve("idempotent_db"))) {
            sql.migrate(Path.of("db", "migration"));

            DocumentStore target = new InMemoryDocumentStore();
            Backfill backfill = new Backfill(sql, target);

            // First run
            Backfill.Result firstRun = backfill.run();
            assertEquals(15, firstRun.read());
            assertEquals(10, firstRun.written());
            assertEquals(5, firstRun.skipped());

            // Second run
            Backfill.Result secondRun = backfill.run();
            assertEquals(15, secondRun.read());
            assertEquals(0, secondRun.written());
            assertEquals(15, secondRun.skipped());

            // Third run
            Backfill.Result thirdRun = backfill.run();
            assertEquals(15, thirdRun.read());
            assertEquals(0, thirdRun.written());
            assertEquals(15, thirdRun.skipped());
        }
    }

    @Test
    @DisplayName("handles partial failure and resumption")
    void handlesPartialFailureAndResumption(@TempDir Path tempDir) throws Exception {
        try (SqlLedgerStore sql = new SqlLedgerStore(tempDir.resolve("partial_db"))) {
            Path emptyDir = tempDir.resolve("migrations");
            java.nio.file.Files.createDirectories(emptyDir);
            java.nio.file.Files.copy(Path.of("db", "migration", "V1__initial.sql"), emptyDir.resolve("V1__initial.sql"));
            sql.migrate(emptyDir);

            NormalizedTxn t1 = txn("4821", "2026-07-01T10:00:00+05:30", Direction.DEBIT,
                    "100.00", Category.SPEND, "STORE1", List.of("m-1"));
            NormalizedTxn t2 = txn("4821", "2026-07-02T10:00:00+05:30", Direction.DEBIT,
                    "200.00", Category.SPEND, "STORE2", List.of("m-2"));
            NormalizedTxn t3 = txn("4821", "2026-07-03T10:00:00+05:30", Direction.DEBIT,
                    "300.00", Category.SPEND, "STORE3", List.of("m-3"));

            sql.save(t1);
            sql.save(t2);
            sql.save(t3);

            // Target already has t1 before backfill starts
            DocumentStore target = new InMemoryDocumentStore();
            target.save(t1);

            Backfill backfill = new Backfill(sql, target);
            Backfill.Result result = backfill.run();

            assertEquals(3, result.read());
            assertEquals(2, result.written()); // only t2 and t3 written
            assertEquals(1, result.skipped()); // t1 skipped

            // All 3 transactions are in target
            assertEquals(3, target.forAccountMonth("4821", YearMonth.of(2026, 7)).size());
        }
    }
}
