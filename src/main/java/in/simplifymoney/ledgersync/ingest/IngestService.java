package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.BalanceSnapshot;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.Amounts;
import in.simplifymoney.ledgersync.parse.Dates;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.LedgerStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads a corpus of raw messages and puts transactions in the ledger.
 *
 * This is the naive version. It parses each message on its own and saves
 * whatever comes back. It does not ask whether two messages describe the same
 * transaction, and it decides the category from the direction alone.
 */
public final class IngestService {

    private static final Pattern BALANCE_ONLY = Pattern.compile(
            "Avl Bal in a/c \\*\\*(?<acct>\\d{4}) is Rs\\.?([0-9,]+\\.[0-9]{2}) as on (?<date>\\d{2}-\\d{2}-\\d{2})");

    private final Parsers parsers;
    private final LedgerStore store;

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        return ingestMessages(readCorpus(corpus));
    }

    public Stats ingestMessages(List<RawMessage> messages) {

        Map<TxnKey, NormalizedTxn> transactions =
                new java.util.LinkedHashMap<>();

        Map<CrossChannelKey, TxnKey> crossChannelIndex =
                new java.util.HashMap<>();

        List<BalanceSnapshot> balanceSnapshots = new ArrayList<>();
        Set<String> seenBalanceAlertKeys = new HashSet<>();

        int skipped = 0;

        for (RawMessage m : messages) {

            Optional<BalanceSnapshot> balOnly = parseBalanceOnly(m);
            if (balOnly.isPresent()) {
                BalanceSnapshot snap = balOnly.get();
                String snapKey = snap.accountLast4() + "|" + snap.observedAt().toLocalDate() + "|" + snap.balance();
                if (seenBalanceAlertKeys.add(snapKey)) {
                    balanceSnapshots.add(snap);
                }
                skipped++;
                continue;
            }

            Optional<ParsedTxn> p = parsers.parse(m);

            if (p.isEmpty()) {
                skipped++;
                continue;
            }

            ParsedTxn parsed = p.get();

            if (parsed.statedBalance() != null && !isCardLimit(m.body())) {
                balanceSnapshots.add(new BalanceSnapshot(
                        parsed.accountLast4(),
                        parsed.occurredAt(),
                        parsed.statedBalance(),
                        parsed.sourceMessageId()
                ));
            }

            TxnKey key = buildKey(parsed);

            /*
             * First check whether this message represents a transaction
             * that we have already seen through another channel.
             */
            CrossChannelKey crossKey = buildCrossChannelKey(parsed);

            TxnKey existingKey = crossChannelIndex.get(crossKey);

            if (existingKey != null) {

                NormalizedTxn existing = transactions.get(existingKey);

                List<String> sourceIds =
                        new ArrayList<>(existing.sourceMessageIds());

                if (!sourceIds.contains(parsed.sourceMessageId())) {
                    sourceIds.add(parsed.sourceMessageId());
                }

                transactions.put(
                        existingKey,
                        new NormalizedTxn(
                                existing.accountLast4(),
                                existing.occurredAt(),
                                existing.direction(),
                                existing.amount(),
                                existing.category(),
                                existing.merchant(),
                                sourceIds
                        )
                );

                continue;
            }

            /*
             * No cross-channel match.
             * Now perform the normal transaction insertion.
             */
            transactions.put(key, toTransaction(parsed));

            /*
             * Remember which transaction this cross-channel key belongs to.
             */
            crossChannelIndex.put(crossKey, key);
        }

        Set<String> existingTxnKeys = new HashSet<>();
        for (NormalizedTxn existing : store.all()) {
            existingTxnKeys.add(transactionKey(existing));
        }

        for (NormalizedTxn txn : transactions.values()) {
            if (!existingTxnKeys.contains(transactionKey(txn))) {
                store.save(txn);
            }
        }

        Set<String> existingSnapshotKeys = new HashSet<>();
        for (BalanceSnapshot existing : store.balanceSnapshots()) {
            existingSnapshotKeys.add(snapshotKey(existing));
        }

        for (BalanceSnapshot s : deduplicateBalanceSnapshots(balanceSnapshots)) {
            if (!existingSnapshotKeys.contains(snapshotKey(s))) {
                store.saveBalanceSnapshot(s);
            }
        }

        return new Stats(
                messages.size(),
                transactions.size(),
                skipped
        );
    }

    public static List<RawMessage> readCorpus(Path corpus) throws IOException {
        List<RawMessage> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(corpus)) {
            for (String line : (Iterable<String>) lines.filter(s -> !s.isBlank())::iterator) {
                Map<String, Object> o = Json.parseObject(line);
                out.add(new RawMessage(
                        (String) o.get("message_id"),
                        (String) o.get("channel"),
                        (String) o.get("sender"),
                        OffsetDateTime.parse((String) o.get("received_at")),
                        (String) o.get("device_id"),
                        (String) o.get("body")));
            }
        }
        return out;
    }

    private NormalizedTxn toTransaction(ParsedTxn p) {
        Category c = Categorizer.categorize(p.direction(), p.amount(), p.merchant());
        return new NormalizedTxn(p.accountLast4(), p.occurredAt(), p.direction(),
                p.amount(), c, p.merchant(), List.of(p.sourceMessageId()));
    }

    private TxnKey buildKey(ParsedTxn parsed) {
        if (parsed.transactionReference() != null) {
            return TxnKey.forReference(
                    parsed.accountLast4(),
                    parsed.transactionReference()
            );
        }

        return TxnKey.forDetails(
                parsed.accountLast4(),
                parsed.occurredAt(),
                parsed.direction(),
                parsed.amount(),
                parsed.merchant(),
                parsed.statedBalance()
        );
    }

    private String normalizeMerchant(String merchant) {
        if (merchant == null) {
            return "";
        }

        return merchant
                .toUpperCase()
                .replaceAll("[^A-Z0-9]", "");
    }

    private CrossChannelKey buildCrossChannelKey(ParsedTxn parsed) {
        return new CrossChannelKey(
                parsed.accountLast4(),
                parsed.occurredAt().toInstant(),
                parsed.direction(),
                parsed.amount(),
                normalizeMerchant(parsed.merchant())
        );
    }

    private Optional<BalanceSnapshot> parseBalanceOnly(RawMessage m) {
        Matcher matcher = BALANCE_ONLY.matcher(m.body());
        if (!matcher.find()) {
            return Optional.empty();
        }
        String acct = matcher.group("acct");
        BigDecimal bal = Amounts.statedBalance(m.body());
        if (bal == null) return Optional.empty();
        String dateStr = matcher.group("date");
        try {
            java.time.LocalDate statedDate = java.time.LocalDate.parse(dateStr,
                    java.time.format.DateTimeFormatter.ofPattern("dd-MM-yy"));
            OffsetDateTime dt;
            if (m.receivedAt().toLocalDate().equals(statedDate)) {
                dt = m.receivedAt();
            } else {
                dt = statedDate.atTime(m.receivedAt().toLocalTime()).atOffset(Dates.IST);
            }
            return Optional.of(new BalanceSnapshot(acct, dt, bal, m.messageId()));
        } catch (Exception e) {
            return Optional.of(new BalanceSnapshot(acct, m.receivedAt(), bal, m.messageId()));
        }
    }

    private List<BalanceSnapshot> deduplicateBalanceSnapshots(List<BalanceSnapshot> snapshots) {
        List<BalanceSnapshot> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (BalanceSnapshot s : snapshots) {
            String key = s.accountLast4() + "|" + s.observedAt() + "|" + s.balance();
            if (seen.add(key)) {
                out.add(s);
            }
        }
        return out;
    }

    private boolean isCardLimit(String body) {
        return body != null && (body.contains("Avl Limit") || body.contains("Card x"));
    }

    private static String transactionKey(NormalizedTxn t) {
        return t.accountLast4() + "|"
                + t.occurredAt().toInstant() + "|"
                + t.direction() + "|"
                + t.amount().setScale(2).toPlainString() + "|"
                + (t.merchant() == null ? "" : t.merchant());
    }

    private static String snapshotKey(BalanceSnapshot s) {
        return s.accountLast4() + "|" + s.observedAt().toInstant() + "|" + s.balance().setScale(2).toPlainString();
    }

    public record Stats(int messagesRead, int transactionsWritten, int messagesSkipped) {}

}
