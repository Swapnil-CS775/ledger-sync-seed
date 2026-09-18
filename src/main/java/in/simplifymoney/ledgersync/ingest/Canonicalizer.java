package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Groups parsed messages into deterministic canonical transactions. */
public final class Canonicalizer {

    private static final Comparator<ParsedTxn> EVIDENCE_ORDER = Comparator
            .comparing(ParsedTxn::sourceMessageId)
            .thenComparing(p -> p.bankReference() == null ? "" : p.bankReference());

    public List<CanonicalTxn> canonicalize(List<ParsedTxn> messages) {
        Map<CanonicalKey, Map<String, ParsedTxn>> grouped = new TreeMap<>();
        for (ParsedTxn message : messages) {
            grouped.computeIfAbsent(CanonicalKey.from(message), ignored -> new LinkedHashMap<>())
                    .putIfAbsent(message.sourceMessageId(), message);
        }

        List<CanonicalTxn> out = new ArrayList<>();
        for (Map.Entry<CanonicalKey, Map<String, ParsedTxn>> entry : grouped.entrySet()) {
            List<ParsedTxn> evidence = new ArrayList<>(entry.getValue().values());
            evidence.sort(EVIDENCE_ORDER);
            out.add(new CanonicalTxn(entry.getKey(), evidence));
        }
        return List.copyOf(out);
    }
}
