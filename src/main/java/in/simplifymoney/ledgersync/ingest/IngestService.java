package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.report.BalanceObservation;
import in.simplifymoney.ledgersync.store.LedgerStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Reads a corpus of raw messages and puts transactions in the ledger.
 *
 * Parsing is deliberately separate from canonicalization: every message keeps
 * its own evidence until Canonicalizer has merged equivalent bank movements.
 * Classification runs only after canonicalization, so category assignment
 * cannot change the canonical transaction count. Stated-balance observations
 * are retained for reconciliation.
 */
public final class IngestService {

    private final Parsers parsers;
    private final LedgerStore store;

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        List<RawMessage> messages = readCorpus(corpus);
        int parsed = 0;
        int skipped = 0;
        List<ParsedTxn> parsedMessages = new ArrayList<>();
        List<BalanceObservation> observations = new ArrayList<>();
        for (RawMessage m : messages) {
            Optional<ParsedTxn> p = parsers.parse(m);
            if (p.isEmpty()) {
                skipped++;
                continue;
            }
            ParsedTxn parsedTxn = p.get();
            parsedMessages.add(parsedTxn);
            parsed++;
            if (parsedTxn.statedBalance() != null) {
                observations.add(BalanceObservation.from(parsedTxn));
            }
        }

        List<CanonicalTxn> canonical = new Canonicalizer().canonicalize(parsedMessages);
        List<NormalizedTxn> classified = Classifier.classify(canonical);
        List<NormalizedTxn> mergedLedger = mergeWithExisting(classified);
        store.replaceAll(mergedLedger);
        return new Stats(messages.size(), parsed, canonical.size(), skipped,
                List.copyOf(observations));
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

    private List<NormalizedTxn> mergeWithExisting(List<NormalizedTxn> classified) {
        Map<CanonicalKey, NormalizedTxn> merged = new TreeMap<>();
        for (NormalizedTxn existing : store.all()) {
            merged.merge(CanonicalKey.from(existing), existing, IngestService::mergeTransactions);
        }
        for (NormalizedTxn transaction : classified) {
            merged.merge(CanonicalKey.from(transaction), transaction, IngestService::mergeTransactions);
        }
        return merged.values().stream()
                .sorted(Comparator.comparing(NormalizedTxn::occurredAt)
                        .thenComparing(NormalizedTxn::accountLast4)
                        .thenComparing(NormalizedTxn::direction)
                        .thenComparing(NormalizedTxn::amount))
                .toList();
    }

    private static NormalizedTxn mergeTransactions(NormalizedTxn left, NormalizedTxn right) {
        List<String> sourceIds = java.util.stream.Stream.concat(
                        left.sourceMessageIds().stream(), right.sourceMessageIds().stream())
                .distinct()
                .sorted()
                .toList();
        NormalizedTxn representative = firstSource(left).compareTo(firstSource(right)) <= 0 ? left : right;
        return new NormalizedTxn(representative.accountLast4(), representative.occurredAt(),
                representative.direction(), representative.amount(), representative.category(),
                representative.merchant(), sourceIds);
    }

    private static String firstSource(NormalizedTxn transaction) {
        return transaction.sourceMessageIds().stream().min(String::compareTo).orElseThrow();
    }

    /** messagesRecognized is pre-deduplication; transactionsWritten is canonical. */
    public record Stats(int messagesRead, int messagesRecognized, int transactionsWritten,
                        int messagesSkipped, List<BalanceObservation> balanceObservations) {
        public Stats {
            balanceObservations = balanceObservations == null
                    ? List.of() : List.copyOf(balanceObservations);
        }

        /** Compatibility constructor for callers that only report written and skipped counts. */
        public Stats(int messagesRead, int transactionsWritten, int messagesSkipped) {
            this(messagesRead, messagesRead - messagesSkipped, transactionsWritten, messagesSkipped,
                    List.of());
        }

        public Stats(int messagesRead, int messagesRecognized, int transactionsWritten,
                     int messagesSkipped) {
            this(messagesRead, messagesRecognized, transactionsWritten, messagesSkipped, List.of());
        }
    }
}
