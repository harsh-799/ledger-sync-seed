package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Moves everything already in the SQL store into the document store.
 *
 * Handles:
 *  - Uniqueness and deduplication for historical unconstrained SQL rows.
 *  - Merging of sourceMessageIds for duplicate rows.
 *  - Safe, idempotent repeated execution and resumption after partial failure.
 */
public final class Backfill {

    private final SqlLedgerStore source;
    private final DocumentStore target;

    public Backfill(SqlLedgerStore source, DocumentStore target) {
        this.source = source;
        this.target = target;
    }

    public Result run() {
        List<NormalizedTxn> sqlRows = source.all();
        long read = sqlRows.size();

        // Deduplicate and consolidate SQL rows
        Map<String, NormalizedTxn> consolidated = new LinkedHashMap<>();
        for (NormalizedTxn row : sqlRows) {
            String key = transactionKey(row);
            NormalizedTxn existing = consolidated.get(key);
            if (existing == null) {
                consolidated.put(key, row);
            } else {
                // Merge source message IDs
                Set<String> mergedIds = new LinkedHashSet<>(existing.sourceMessageIds());
                mergedIds.addAll(row.sourceMessageIds());
                List<String> sortedIds = new ArrayList<>(mergedIds);
                Collections.sort(sortedIds);

                consolidated.put(key, new NormalizedTxn(
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

        long written = 0;
        for (NormalizedTxn candidate : consolidated.values()) {
            boolean alreadyPresent = false;
            List<NormalizedTxn> existingMonthTxns = target.forAccountMonth(
                    candidate.accountLast4(),
                    YearMonth.from(candidate.occurredAt())
            );

            for (NormalizedTxn existing : existingMonthTxns) {
                if (matchesBusinessIdentity(existing, candidate)) {
                    if (existing.sourceMessageIds().containsAll(candidate.sourceMessageIds())) {
                        alreadyPresent = true;
                    }
                    break;
                }
            }

            if (!alreadyPresent) {
                target.save(candidate);
                written++;
            }
        }

        long skipped = read - written;
        return new Result(read, written, skipped);
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

    public record Result(long read, long written, long skipped) {}
}
