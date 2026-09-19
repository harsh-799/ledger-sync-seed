package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.BalanceSnapshot;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Used by SelfCheck and by tests. Keeps everything it is given. */
public final class InMemoryLedgerStore implements LedgerStore {

    private final List<NormalizedTxn> rows = new ArrayList<>();
    private final List<BalanceSnapshot> snapshots = new ArrayList<>();

    @Override public void save(NormalizedTxn txn) { rows.add(txn); }

    @Override public List<NormalizedTxn> all() { return Collections.unmodifiableList(rows); }

    @Override public long count() { return rows.size(); }

    @Override public void saveBalanceSnapshot(BalanceSnapshot snapshot) { snapshots.add(snapshot); }

    @Override public List<BalanceSnapshot> balanceSnapshots() { return Collections.unmodifiableList(snapshots); }
}
