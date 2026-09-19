package in.simplifymoney.ledgersync.report;

import in.simplifymoney.ledgersync.model.BalanceSnapshot;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The two reports the assignment asks for.
 *
 * summary() below is a first cut: it adds up what is in the ledger. It does not
 * know that a transfer is not spending, and it does not roll micro spends up.
 *
 * reconciliation() has not been written at all.
 */
public final class Reports {

    private Reports() {}

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    public static Map<String, Object> summary(List<NormalizedTxn> ledger) {
        Map<String, Object> accounts = new LinkedHashMap<>();
        for (String acct : new TreeSet<>(ledger.stream()
                .map(NormalizedTxn::accountLast4).toList())) {

            BigDecimal spend = ZERO;
            BigDecimal income = ZERO;
            BigDecimal microTotal = ZERO;
            int microCount = 0;
            BigDecimal transferredOut = ZERO;
            BigDecimal transferredIn = ZERO;

            for (NormalizedTxn t : ledger) {
                if (!t.accountLast4().equals(acct)) continue;
                switch (t.category()) {
                    case SPEND -> spend = spend.add(t.amount());
                    case INCOME -> income = income.add(t.amount());
                    case MICRO -> {
                        microTotal = microTotal.add(t.amount());
                        microCount++;
                    }
                    case TRANSFER -> {
                        if (t.direction() == Direction.DEBIT) {
                            transferredOut = transferredOut.add(t.amount());
                        } else {
                            transferredIn = transferredIn.add(t.amount());
                        }
                    }
                }
            }

            Map<String, Object> a = new LinkedHashMap<>();
            a.put("spend", spend.toPlainString());
            a.put("income", income.toPlainString());
            a.put("micro_count", microCount);
            a.put("micro_total", microTotal.toPlainString());
            a.put("transferred_out", transferredOut.toPlainString());
            a.put("transferred_in", transferredIn.toPlainString());
            accounts.put(acct, a);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("accounts", accounts);
        return doc;
    }

    public static Map<String, Object> ledgerDocument(List<NormalizedTxn> ledger) {
        List<Object> rows = ledger.stream().map(t -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("account_last4", t.accountLast4());
            r.put("occurred_at", t.occurredAt().toString());
            r.put("direction", t.direction().name().toLowerCase());
            r.put("amount", t.amount().toPlainString());
            r.put("category", t.category().name());
            r.put("merchant", t.merchant());
            r.put("source_message_ids", t.sourceMessageIds());
            return (Object) r;
        }).toList();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("transactions", rows);
        return doc;
    }

    public static Map<String, Object> reconciliation(List<NormalizedTxn> ledger) {
        return reconciliation(ledger, List.of());
    }

    public static Map<String, Object> reconciliation(
            List<NormalizedTxn> ledger,
            List<BalanceSnapshot> balanceSnapshots) {

        List<Map<String, Object>> discrepancies = new ArrayList<>();

        if (balanceSnapshots == null || balanceSnapshots.isEmpty()) {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("discrepancies", discrepancies);
            return doc;
        }

        Map<String, List<BalanceSnapshot>> snapshotsByAccount = balanceSnapshots.stream()
                .collect(Collectors.groupingBy(BalanceSnapshot::accountLast4));

        Map<String, List<NormalizedTxn>> txnsByAccount = ledger.stream()
                .collect(Collectors.groupingBy(NormalizedTxn::accountLast4));

        for (String account : new TreeSet<>(snapshotsByAccount.keySet())) {
            List<BalanceSnapshot> rawSnapshots = snapshotsByAccount.get(account);
            if (rawSnapshots == null || rawSnapshots.isEmpty()) {
                continue;
            }

            List<BalanceSnapshot> sorted = rawSnapshots.stream()
                    .sorted(Comparator.comparing(BalanceSnapshot::observedAt))
                    .toList();

            List<BalanceSnapshot> deduplicated = new ArrayList<>();
            for (BalanceSnapshot s : sorted) {
                if (deduplicated.isEmpty()) {
                    deduplicated.add(s);
                } else {
                    BalanceSnapshot last = deduplicated.get(deduplicated.size() - 1);
                    if (!last.observedAt().equals(s.observedAt()) || !last.balance().equals(s.balance())) {
                        deduplicated.add(s);
                    }
                }
            }

            List<NormalizedTxn> acctTxns = txnsByAccount.getOrDefault(account, List.of());

            for (int i = 1; i < deduplicated.size(); i++) {
                BalanceSnapshot prev = deduplicated.get(i - 1);
                BalanceSnapshot curr = deduplicated.get(i);

                if (curr.observedAt().isBefore(prev.observedAt())) {
                    continue;
                }

                List<NormalizedTxn> intermediateTxns = acctTxns.stream()
                        .filter(t -> t.occurredAt().isAfter(prev.observedAt())
                                && !t.occurredAt().isAfter(curr.observedAt()))
                        .toList();

                if (intermediateTxns.isEmpty() && curr.observedAt().equals(prev.observedAt())) {
                    continue;
                }

                BigDecimal netMovement = BigDecimal.ZERO.setScale(2);
                for (NormalizedTxn t : intermediateTxns) {
                    if (t.direction() == Direction.CREDIT) {
                        netMovement = netMovement.add(t.amount());
                    } else {
                        netMovement = netMovement.subtract(t.amount());
                    }
                }

                BigDecimal expectedBal = prev.balance().add(netMovement);
                BigDecimal diff = curr.balance().subtract(expectedBal);

                if (diff.compareTo(BigDecimal.ZERO) != 0) {
                    Map<String, Object> discrepancy = new LinkedHashMap<>();
                    discrepancy.put("account_last4", account);
                    discrepancy.put("occurred_at", curr.observedAt().toString());
                    discrepancy.put("amount", diff.abs().setScale(2).toPlainString());
                    discrepancy.put("note", String.format(
                            "Bank stated balance changed from %s at %s to %s at %s, but ledger transactions account for net movement of %s (unexplained difference of %s)",
                            prev.balance().toPlainString(),
                            prev.observedAt(),
                            curr.balance().toPlainString(),
                            curr.observedAt(),
                            netMovement.toPlainString(),
                            diff.toPlainString()
                    ));
                    discrepancies.add(discrepancy);
                }
            }
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("discrepancies", discrepancies);
        return doc;
    }

    public static Map<Category, BigDecimal> byCategory(List<NormalizedTxn> ledger) {
        Map<Category, BigDecimal> out = new LinkedHashMap<>();
        for (Category c : Category.values()) out.put(c, ZERO);
        for (NormalizedTxn t : ledger) {
            out.put(t.category(), out.get(t.category()).add(t.amount()));
        }
        return out;
    }
}
