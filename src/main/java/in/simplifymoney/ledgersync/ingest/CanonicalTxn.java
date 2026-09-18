package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.util.List;

/** One canonical movement plus every parsed message that evidences it. */
public record CanonicalTxn(CanonicalKey key, List<ParsedTxn> evidence) {

    public CanonicalTxn {
        evidence = List.copyOf(evidence);
        if (evidence.isEmpty()) throw new IllegalArgumentException("canonical transaction needs evidence");
    }
}
