package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.NormalizedTxn;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Proves the two stores agree, and says precisely where they do not.
 *
 * Audits bidirectional consistency:
 * - Missing transactions in DocumentStore
 * - Extra transactions in DocumentStore
 * - Field-level discrepancies (amount, direction, category, occurredAt, merchant, sourceMessageIds)
 * - Category running totals per account
 * - Message ID index resolution
 */
public final class ConsistencyChecker {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final SqlLedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(SqlLedgerStore sql, DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    public List<Divergence> check() {
        List<Divergence> divergences = new ArrayList<>();

        // 1. Read and deduplicate SQL transactions to form the canonical expected ledger
        List<NormalizedTxn> sqlRows = sql.all();
        Map<String, NormalizedTxn> expectedSqlTxns = new LinkedHashMap<>();

        for (NormalizedTxn row : sqlRows) {
            String key = transactionKey(row);
            NormalizedTxn existing = expectedSqlTxns.get(key);
            if (existing == null) {
                expectedSqlTxns.put(key, row);
            } else {
                Set<String> mergedIds = new LinkedHashSet<>(existing.sourceMessageIds());
                mergedIds.addAll(row.sourceMessageIds());
                List<String> sortedIds = new ArrayList<>(mergedIds);
                Collections.sort(sortedIds);

                expectedSqlTxns.put(key, new NormalizedTxn(
                        existing.accountLast4(),
                        existing.occurredAt(),
                        existing.direction(),
                        existing.amount(),
                        existing.category(),
                        existing.merchant(),
                        sortedIds
                ));
            }
        }

        // Collect all accounts and months from SQL
        Set<String> allAccounts = new TreeSet<>();
        Set<AccountMonthKey> accountMonths = new HashSet<>();
        for (NormalizedTxn t : expectedSqlTxns.values()) {
            allAccounts.add(t.accountLast4());
            accountMonths.add(new AccountMonthKey(t.accountLast4(), YearMonth.from(t.occurredAt())));
        }

        // Discover any extra accounts or months in documents if accessible
        if (documents instanceof InMemoryDocumentStore mem) {
            for (NormalizedTxn t : mem.all()) {
                allAccounts.add(t.accountLast4());
                accountMonths.add(new AccountMonthKey(t.accountLast4(), YearMonth.from(t.occurredAt())));
            }
        }

        // 2. Check transactions by account and month
        Set<String> matchedSqlKeys = new HashSet<>();

        for (AccountMonthKey amk : accountMonths) {
            List<NormalizedTxn> docTxns = documents.forAccountMonth(amk.accountLast4(), amk.month());

            // Check ordering: newest first
            for (int i = 1; i < docTxns.size(); i++) {
                if (docTxns.get(i).occurredAt().isAfter(docTxns.get(i - 1).occurredAt())) {
                    divergences.add(new Divergence(
                            "ordering_violation:" + amk.accountLast4() + ":" + amk.month(),
                            "descending occurredAt",
                            "out of order at index " + i
                    ));
                    break;
                }
            }

            for (NormalizedTxn docTxn : docTxns) {
                // Find matching SQL transaction:
                // Primary match by exact transaction key
                NormalizedTxn expected = expectedSqlTxns.get(transactionKey(docTxn));

                // If not found by exact key, correlate by overlapping sourceMessageIds or (account + timestamp)
                if (expected == null) {
                    for (NormalizedTxn candidate : expectedSqlTxns.values()) {
                        if (!Collections.disjoint(candidate.sourceMessageIds(), docTxn.sourceMessageIds())
                                || (candidate.accountLast4().equals(docTxn.accountLast4())
                                && candidate.occurredAt().toInstant().equals(docTxn.occurredAt().toInstant())
                                && Objects.equals(candidate.merchant(), docTxn.merchant()))) {
                            expected = candidate;
                            break;
                        }
                    }
                }

                if (expected == null) {
                    // Extra transaction in documents
                    divergences.add(new Divergence(
                            "extra_transaction_in_documents:" + transactionKey(docTxn),
                            "<missing>",
                            docTxn.toString()
                    ));
                } else {
                    matchedSqlKeys.add(transactionKey(expected));
                    compareFields(expected, docTxn, divergences);
                }
            }
        }

        // 3. Check for missing transactions in documents
        for (Map.Entry<String, NormalizedTxn> entry : expectedSqlTxns.entrySet()) {
            String key = entry.getKey();
            NormalizedTxn expected = entry.getValue();
            if (!matchedSqlKeys.contains(key)) {
                divergences.add(new Divergence(
                        "missing_transaction_in_documents:" + key,
                        expected.toString(),
                        "<missing>"
                ));
            }
        }

        // 4. Check byMessageId for each message ID in SQL
        for (NormalizedTxn expected : expectedSqlTxns.values()) {
            for (String msgId : expected.sourceMessageIds()) {
                Optional<NormalizedTxn> byMsg = documents.byMessageId(msgId);
                if (byMsg.isEmpty()) {
                    divergences.add(new Divergence(
                            "by_message_id_missing:" + msgId,
                            expected.toString(),
                            "<empty>"
                    ));
                } else if (!matchesBusinessIdentity(byMsg.get(), expected)) {
                    divergences.add(new Divergence(
                            "by_message_id_mismatch:" + msgId,
                            expected.toString(),
                            byMsg.get().toString()
                    ));
                }
            }
        }

        // 5. Check category totals for each account
        for (String account : allAccounts) {
            Map<Category, BigDecimal> docTotals = documents.categoryTotals(account);
            Map<Category, BigDecimal> expectedTotals = computeExpectedCategoryTotals(account, expectedSqlTxns.values());

            for (Category c : Category.values()) {
                BigDecimal expectedTotal = expectedTotals.getOrDefault(c, ZERO);
                BigDecimal docTotal = docTotals.getOrDefault(c, ZERO);
                if (expectedTotal.compareTo(docTotal) != 0) {
                    divergences.add(new Divergence(
                            "category_total_mismatch:" + account + ":" + c,
                            expectedTotal.toPlainString(),
                            docTotal.toPlainString()
                    ));
                }
            }
        }

        return divergences;
    }

    private void compareFields(NormalizedTxn sqlTxn, NormalizedTxn docTxn, List<Divergence> divergences) {
        String key = transactionKey(sqlTxn);

        if (sqlTxn.amount().compareTo(docTxn.amount()) != 0) {
            divergences.add(new Divergence(
                    "amount_mismatch:" + key,
                    sqlTxn.amount().toPlainString(),
                    docTxn.amount().toPlainString()
            ));
        }

        if (sqlTxn.direction() != docTxn.direction()) {
            divergences.add(new Divergence(
                    "direction_mismatch:" + key,
                    sqlTxn.direction().name(),
                    docTxn.direction().name()
            ));
        }

        if (sqlTxn.category() != docTxn.category()) {
            divergences.add(new Divergence(
                    "category_mismatch:" + key,
                    sqlTxn.category().name(),
                    docTxn.category().name()
            ));
        }

        if (!sqlTxn.occurredAt().isEqual(docTxn.occurredAt())) {
            divergences.add(new Divergence(
                    "occurred_at_mismatch:" + key,
                    sqlTxn.occurredAt().toString(),
                    docTxn.occurredAt().toString()
            ));
        }

        if (!Objects.equals(sqlTxn.merchant(), docTxn.merchant())) {
            divergences.add(new Divergence(
                    "merchant_mismatch:" + key,
                    sqlTxn.merchant(),
                    docTxn.merchant()
            ));
        }

        Set<String> sqlIds = new HashSet<>(sqlTxn.sourceMessageIds());
        Set<String> docIds = new HashSet<>(docTxn.sourceMessageIds());
        if (!sqlIds.equals(docIds)) {
            divergences.add(new Divergence(
                    "source_message_ids_mismatch:" + key,
                    sqlTxn.sourceMessageIds().toString(),
                    docTxn.sourceMessageIds().toString()
            ));
        }
    }

    private Map<Category, BigDecimal> computeExpectedCategoryTotals(String account, Iterable<NormalizedTxn> txns) {
        Map<Category, BigDecimal> totals = new EnumMap<>(Category.class);
        for (Category c : Category.values()) {
            totals.put(c, ZERO);
        }
        for (NormalizedTxn t : txns) {
            if (t.accountLast4().equals(account)) {
                totals.merge(t.category(), t.amount().setScale(2), (a, b) -> a.add(b).setScale(2));
            }
        }
        return totals;
    }

    private static String transactionKey(NormalizedTxn t) {
        return t.accountLast4() + "|"
                + t.occurredAt().toInstant() + "|"
                + t.direction() + "|"
                + t.amount().setScale(2).toPlainString() + "|"
                + (t.merchant() == null ? "" : t.merchant());
    }

    private static boolean matchesBusinessIdentity(NormalizedTxn a, NormalizedTxn b) {
        return a.accountLast4().equals(b.accountLast4())
                && a.occurredAt().toInstant().equals(b.occurredAt().toInstant())
                && a.direction() == b.direction()
                && a.amount().compareTo(b.amount()) == 0
                && (a.merchant() == null ? "" : a.merchant()).equals(b.merchant() == null ? "" : b.merchant());
    }

    private record AccountMonthKey(String accountLast4, YearMonth month) {}

    /** One place the two stores disagree. */
    public record Divergence(String what, String inSql, String inDocuments) {}
}
