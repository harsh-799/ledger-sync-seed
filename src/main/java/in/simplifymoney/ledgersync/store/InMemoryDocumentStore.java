package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.NormalizedTxn;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory implementation of DocumentStore using standard Java collections.
 *
 * Implements the three document store access patterns:
 * Q1: forAccountMonth (newest first)
 * Q2: categoryTotals (all 4 categories, scale 2)
 * Q3: byMessageId (each source message ID maps to its transaction)
 *
 * Fully idempotent on repeated saves.
 */
public final class InMemoryDocumentStore implements DocumentStore {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    // Primary store: Key -> NormalizedTxn
    private final Map<String, NormalizedTxn> transactions = new LinkedHashMap<>();

    // Message ID index: messageId -> primaryKey
    private final Map<String, String> messageIdToKey = new HashMap<>();

    // Running category totals: accountLast4 -> Category -> total
    private final Map<String, Map<Category, BigDecimal>> totalsByAccount = new HashMap<>();

    @Override
    public synchronized void save(NormalizedTxn txn) {
        Objects.requireNonNull(txn, "txn cannot be null");

        String primaryKey = txnKey(txn);
        NormalizedTxn existing = transactions.get(primaryKey);

        // Also check if any of the txn's message IDs already point to an existing transaction
        if (existing == null) {
            for (String msgId : txn.sourceMessageIds()) {
                String existingKey = messageIdToKey.get(msgId);
                if (existingKey != null && transactions.containsKey(existingKey)) {
                    existing = transactions.get(existingKey);
                    primaryKey = existingKey;
                    break;
                }
            }
        }

        if (existing != null) {
            // Already present: merge source message IDs and adjust totals if category/amount changed
            Set<String> mergedMessageIds = new LinkedHashSet<>(existing.sourceMessageIds());
            mergedMessageIds.addAll(txn.sourceMessageIds());
            List<String> sortedMessageIds = new ArrayList<>(mergedMessageIds);
            Collections.sort(sortedMessageIds);

            boolean categoryOrAmountChanged = !existing.category().equals(txn.category())
                    || existing.amount().compareTo(txn.amount()) != 0;

            if (categoryOrAmountChanged) {
                subtractTotal(existing.accountLast4(), existing.category(), existing.amount());
                addTotal(txn.accountLast4(), txn.category(), txn.amount());
            }

            NormalizedTxn updated = new NormalizedTxn(
                    txn.accountLast4(),
                    txn.occurredAt(),
                    txn.direction(),
                    txn.amount(),
                    txn.category(),
                    txn.merchant(),
                    sortedMessageIds
            );

            transactions.put(primaryKey, updated);
            for (String msgId : sortedMessageIds) {
                messageIdToKey.put(msgId, primaryKey);
            }
            return;
        }

        // New transaction
        transactions.put(primaryKey, txn);
        for (String msgId : txn.sourceMessageIds()) {
            messageIdToKey.put(msgId, primaryKey);
        }
        addTotal(txn.accountLast4(), txn.category(), txn.amount());
    }

    @Override
    public synchronized List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        if (accountLast4 == null || month == null) {
            return List.of();
        }

        return transactions.values().stream()
                .filter(t -> t.accountLast4().equals(accountLast4)
                        && YearMonth.from(t.occurredAt()).equals(month))
                .sorted(Comparator.comparing(NormalizedTxn::occurredAt).reversed())
                .toList();
    }

    @Override
    public synchronized Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        Map<Category, BigDecimal> result = new LinkedHashMap<>();
        for (Category c : Category.values()) {
            result.put(c, ZERO);
        }

        if (accountLast4 == null) {
            return result;
        }

        Map<Category, BigDecimal> acctTotals = totalsByAccount.get(accountLast4);
        if (acctTotals != null) {
            for (Category c : Category.values()) {
                BigDecimal total = acctTotals.getOrDefault(c, ZERO);
                result.put(c, total.setScale(2));
            }
        }
        return result;
    }

    @Override
    public synchronized Optional<NormalizedTxn> byMessageId(String messageId) {
        if (messageId == null) {
            return Optional.empty();
        }
        String key = messageIdToKey.get(messageId);
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(transactions.get(key));
    }

    public synchronized int count() {
        return transactions.size();
    }

    public synchronized List<NormalizedTxn> all() {
        return List.copyOf(transactions.values());
    }

    private void addTotal(String account, Category category, BigDecimal amount) {
        totalsByAccount.computeIfAbsent(account, k -> new EnumMap<>(Category.class))
                .merge(category, amount.setScale(2), (a, b) -> a.add(b).setScale(2));
    }

    private void subtractTotal(String account, Category category, BigDecimal amount) {
        Map<Category, BigDecimal> acctTotals = totalsByAccount.get(account);
        if (acctTotals != null) {
            acctTotals.computeIfPresent(category, (c, current) -> current.subtract(amount).setScale(2));
        }
    }

    private String txnKey(NormalizedTxn t) {
        return t.accountLast4() + "|"
                + t.occurredAt().toInstant() + "|"
                + t.direction() + "|"
                + t.amount().setScale(2).toPlainString() + "|"
                + t.merchant();
    }
}
