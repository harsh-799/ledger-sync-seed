package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.LedgerStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads a corpus of raw messages and puts transactions in the ledger.
 *
 * This is the naive version. It parses each message on its own and saves
 * whatever comes back. It does not ask whether two messages describe the same
 * transaction, and it decides the category from the direction alone.
 */
public final class IngestService {

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
        Map<TxnKey, NormalizedTxn> transactions = new java.util.LinkedHashMap<>();

        int skipped = 0;

        for (RawMessage m : messages) {
            Optional<ParsedTxn> p = parsers.parse(m);

            if (p.isEmpty()) {
                skipped++;
                continue;
            }

            ParsedTxn parsed = p.get();

            TxnKey key = buildKey(parsed);

            NormalizedTxn existing = transactions.get(key);

            if (existing == null) {
                transactions.put(key, toTransaction(parsed));
            } else {
                List<String> sourceIds = new ArrayList<>(existing.sourceMessageIds());
                sourceIds.add(parsed.sourceMessageId());

                transactions.put(
                        key,
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
            }
        }

        for (NormalizedTxn txn : transactions.values()) {
            store.save(txn);
        }

        return new Stats(messages.size(), transactions.size(), skipped);
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
        Category c = p.direction() == Direction.DEBIT ? Category.SPEND : Category.INCOME;
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

    public record Stats(int messagesRead, int transactionsWritten, int messagesSkipped) {}

}
