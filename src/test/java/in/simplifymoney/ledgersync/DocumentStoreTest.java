package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.DocumentStore;
import in.simplifymoney.ledgersync.store.InMemoryDocumentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentStoreTest {

    private DocumentStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryDocumentStore();
    }

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

    @Nested
    @DisplayName("Q1: forAccountMonth")
    class MonthlyTransactionsTests {

        @Test
        @DisplayName("save and retrieve by account and month")
        void saveAndRetrieveByAccountMonth() {
            NormalizedTxn t1 = txn("4821", "2026-07-04T12:00:00+05:30", Direction.DEBIT,
                    "150.00", Category.SPEND, "AMAZON", List.of("m-1"));
            store.save(t1);

            List<NormalizedTxn> result = store.forAccountMonth("4821", YearMonth.of(2026, 7));
            assertEquals(1, result.size());
            assertEquals(t1, result.get(0));
        }

        @Test
        @DisplayName("correct month filtering: ignores transactions from other months")
        void filtersByCorrectMonth() {
            NormalizedTxn juneTxn = txn("4821", "2026-06-30T23:59:00+05:30", Direction.DEBIT,
                    "50.00", Category.SPEND, "SWIGGY", List.of("m-june"));
            NormalizedTxn julyTxn = txn("4821", "2026-07-01T00:01:00+05:30", Direction.DEBIT,
                    "100.00", Category.SPEND, "ZOMATO", List.of("m-july"));
            NormalizedTxn augustTxn = txn("4821", "2026-08-01T12:00:00+05:30", Direction.DEBIT,
                    "200.00", Category.SPEND, "BIGBASKET", List.of("m-august"));

            store.save(juneTxn);
            store.save(julyTxn);
            store.save(augustTxn);

            List<NormalizedTxn> julyOnly = store.forAccountMonth("4821", YearMonth.of(2026, 7));
            assertEquals(1, julyOnly.size());
            assertEquals(julyTxn, julyOnly.get(0));

            List<NormalizedTxn> juneOnly = store.forAccountMonth("4821", YearMonth.of(2026, 6));
            assertEquals(1, juneOnly.size());
            assertEquals(juneTxn, juneOnly.get(0));

            List<NormalizedTxn> augustOnly = store.forAccountMonth("4821", YearMonth.of(2026, 8));
            assertEquals(1, augustOnly.size());
            assertEquals(augustTxn, augustOnly.get(0));
        }

        @Test
        @DisplayName("descending chronological ordering: newest transaction first")
        void returnsNewestFirst() {
            NormalizedTxn morning = txn("4821", "2026-07-10T09:00:00+05:30", Direction.DEBIT,
                    "20.00", Category.MICRO, "CHAI", List.of("m-1"));
            NormalizedTxn afternoon = txn("4821", "2026-07-10T14:30:00+05:30", Direction.DEBIT,
                    "450.00", Category.SPEND, "LUNCH", List.of("m-2"));
            NormalizedTxn evening = txn("4821", "2026-07-10T21:15:00+05:30", Direction.DEBIT,
                    "899.00", Category.SPEND, "DINNER", List.of("m-3"));

            // Save out of chronological order
            store.save(afternoon);
            store.save(morning);
            store.save(evening);

            List<NormalizedTxn> result = store.forAccountMonth("4821", YearMonth.of(2026, 7));
            assertEquals(3, result.size());
            assertEquals(evening, result.get(0));
            assertEquals(afternoon, result.get(1));
            assertEquals(morning, result.get(2));
        }

        @Test
        @DisplayName("empty list when account or month has no transactions")
        void emptyWhenNoTransactions() {
            List<NormalizedTxn> empty = store.forAccountMonth("9999", YearMonth.of(2026, 7));
            assertTrue(empty.isEmpty());
        }
    }

    @Nested
    @DisplayName("Q2: categoryTotals")
    class CategoryTotalsTests {

        @Test
        @DisplayName("category totals for all four categories with scale 2")
        void totalsForAllFourCategories() {
            store.save(txn("4821", "2026-07-01T10:00:00+05:30", Direction.DEBIT,
                    "1500.00", Category.SPEND, "SHOPPING", List.of("m-1")));
            store.save(txn("4821", "2026-07-02T10:00:00+05:30", Direction.CREDIT,
                    "50000.00", Category.INCOME, "SALARY", List.of("m-2")));
            store.save(txn("4821", "2026-07-03T10:00:00+05:30", Direction.DEBIT,
                    "35.50", Category.MICRO, "UPI/TEA", List.of("m-3")));
            store.save(txn("4821", "2026-07-04T10:00:00+05:30", Direction.DEBIT,
                    "8000.00", Category.TRANSFER, "IMPS/PARAG KAPOOR", List.of("m-4")));

            Map<Category, BigDecimal> totals = store.categoryTotals("4821");

            assertEquals(new BigDecimal("1500.00"), totals.get(Category.SPEND));
            assertEquals(new BigDecimal("50000.00"), totals.get(Category.INCOME));
            assertEquals(new BigDecimal("35.50"), totals.get(Category.MICRO));
            assertEquals(new BigDecimal("8000.00"), totals.get(Category.TRANSFER));

            for (Category c : Category.values()) {
                assertEquals(2, totals.get(c).scale());
            }
        }

        @Test
        @DisplayName("zero totals (0.00) for categories with no transactions")
        void zeroTotalsForEmptyCategories() {
            // Account with only SPEND
            store.save(txn("4821", "2026-07-01T10:00:00+05:30", Direction.DEBIT,
                    "250.00", Category.SPEND, "CAFE", List.of("m-1")));

            Map<Category, BigDecimal> totals = store.categoryTotals("4821");
            assertEquals(new BigDecimal("250.00"), totals.get(Category.SPEND));
            assertEquals(new BigDecimal("0.00"), totals.get(Category.INCOME));
            assertEquals(new BigDecimal("0.00"), totals.get(Category.MICRO));
            assertEquals(new BigDecimal("0.00"), totals.get(Category.TRANSFER));

            // Account with no transactions at all
            Map<Category, BigDecimal> emptyTotals = store.categoryTotals("9075");
            for (Category c : Category.values()) {
                assertEquals(new BigDecimal("0.00"), emptyTotals.get(c));
                assertEquals(2, emptyTotals.get(c).scale());
            }
        }
    }

    @Nested
    @DisplayName("Q3: byMessageId")
    class MessageIdLookupTests {

        @Test
        @DisplayName("lookup by each source message ID when multiple IDs evidence one transaction")
        void lookupByEachSourceMessageId() {
            NormalizedTxn mergedTxn = txn("4821", "2026-07-04T20:24:00+05:30", Direction.DEBIT,
                    "2499.50", Category.SPEND, "AMAZON PAY",
                    List.of("m-sms-001", "m-email-002", "m-push-003"));

            store.save(mergedTxn);

            Optional<NormalizedTxn> bySms = store.byMessageId("m-sms-001");
            Optional<NormalizedTxn> byEmail = store.byMessageId("m-email-002");
            Optional<NormalizedTxn> byPush = store.byMessageId("m-push-003");

            assertTrue(bySms.isPresent());
            assertTrue(byEmail.isPresent());
            assertTrue(byPush.isPresent());

            assertEquals(mergedTxn, bySms.get());
            assertEquals(mergedTxn, byEmail.get());
            assertEquals(mergedTxn, byPush.get());
        }

        @Test
        @DisplayName("returns Optional.empty() for unknown or null message ID")
        void unknownMessageIdReturnsEmpty() {
            assertFalse(store.byMessageId("non-existent-id").isPresent());
            assertFalse(store.byMessageId(null).isPresent());
        }
    }

    @Nested
    @DisplayName("Idempotency and Updates")
    class IdempotencyTests {

        @Test
        @DisplayName("saving the exact same transaction repeatedly does not duplicate records or double-count totals")
        void exactDuplicateSaveIsIdempotent() {
            NormalizedTxn t = txn("4821", "2026-07-04T12:00:00+05:30", Direction.DEBIT,
                    "500.00", Category.SPEND, "RELIANCE", List.of("m-1"));

            store.save(t);
            store.save(t);
            store.save(t);

            List<NormalizedTxn> txns = store.forAccountMonth("4821", YearMonth.of(2026, 7));
            assertEquals(1, txns.size());

            Map<Category, BigDecimal> totals = store.categoryTotals("4821");
            assertEquals(new BigDecimal("500.00"), totals.get(Category.SPEND));
        }

        @Test
        @DisplayName("saving an updated transaction with additional source message IDs merges IDs without double-counting")
        void mergesMessageIdsWithoutDoubleCounting() {
            NormalizedTxn first = txn("4821", "2026-07-04T12:00:00+05:30", Direction.DEBIT,
                    "500.00", Category.SPEND, "RELIANCE", List.of("m-sms"));
            store.save(first);

            NormalizedTxn second = txn("4821", "2026-07-04T12:00:00+05:30", Direction.DEBIT,
                    "500.00", Category.SPEND, "RELIANCE", List.of("m-sms", "m-email"));
            store.save(second);

            List<NormalizedTxn> txns = store.forAccountMonth("4821", YearMonth.of(2026, 7));
            assertEquals(1, txns.size());
            assertEquals(List.of("m-email", "m-sms"), txns.get(0).sourceMessageIds());

            // Both IDs resolve to the updated transaction
            assertTrue(store.byMessageId("m-sms").isPresent());
            assertTrue(store.byMessageId("m-email").isPresent());
            assertEquals(txns.get(0), store.byMessageId("m-sms").get());
            assertEquals(txns.get(0), store.byMessageId("m-email").get());

            // Totals are still only 500.00
            assertEquals(new BigDecimal("500.00"), store.categoryTotals("4821").get(Category.SPEND));
        }
    }

    @Nested
    @DisplayName("Multiple Accounts and Multiple Months")
    class MultiAccountMonthTests {

        @Test
        @DisplayName("isolation across multiple accounts and months")
        void isolatesAccountsAndMonths() {
            NormalizedTxn acct1July = txn("4821", "2026-07-15T10:00:00+05:30", Direction.DEBIT,
                    "100.00", Category.SPEND, "STORE A", List.of("m-1"));
            NormalizedTxn acct1August = txn("4821", "2026-08-15T10:00:00+05:30", Direction.DEBIT,
                    "200.00", Category.SPEND, "STORE B", List.of("m-2"));
            NormalizedTxn acct2July = txn("9075", "2026-07-20T10:00:00+05:30", Direction.CREDIT,
                    "500.00", Category.INCOME, "SALARY", List.of("m-3"));

            store.save(acct1July);
            store.save(acct1August);
            store.save(acct2July);

            // Account 4821 queries
            assertEquals(List.of(acct1July), store.forAccountMonth("4821", YearMonth.of(2026, 7)));
            assertEquals(List.of(acct1August), store.forAccountMonth("4821", YearMonth.of(2026, 8)));
            assertEquals(new BigDecimal("300.00"), store.categoryTotals("4821").get(Category.SPEND));
            assertEquals(new BigDecimal("0.00"), store.categoryTotals("4821").get(Category.INCOME));

            // Account 9075 queries
            assertEquals(List.of(acct2July), store.forAccountMonth("9075", YearMonth.of(2026, 7)));
            assertEquals(List.of(), store.forAccountMonth("9075", YearMonth.of(2026, 8)));
            assertEquals(new BigDecimal("0.00"), store.categoryTotals("9075").get(Category.SPEND));
            assertEquals(new BigDecimal("500.00"), store.categoryTotals("9075").get(Category.INCOME));
        }
    }
}
