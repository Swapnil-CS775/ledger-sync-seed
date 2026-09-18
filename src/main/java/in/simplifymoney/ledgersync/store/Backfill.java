package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.ingest.CanonicalKey;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Moves everything already in the SQL store into the document store.
 *
 * The SQL ledger has no uniqueness guarantee, so rows are collapsed with the
 * same {@link CanonicalKey} identity used by ingest before each upsert.
 *
 * {@link Result} meanings:
 * <ul>
 *   <li>{@code read} — SQL rows examined</li>
 *   <li>{@code written} — distinct logical transactions upserted</li>
 *   <li>{@code skipped} — legacy duplicate SQL rows collapsed away
 *       ({@code read - written})</li>
 * </ul>
 *
 * Safe to re-run: upserts are idempotent, source message ids are merged with
 * any document already present for the same canonical key, and duplicate SQL
 * rows never become duplicate documents.
 */
public final class Backfill {

    private final SqlLedgerStore source;
    private final DocumentStore target;

    public Backfill(SqlLedgerStore source, DocumentStore target) {
        this.source = source;
        this.target = target;
    }

    public Result run() {
        List<NormalizedTxn> rows = source.all();
        long read = rows.size();

        Map<CanonicalKey, NormalizedTxn> collapsed = new TreeMap<>();
        for (NormalizedTxn row : rows) {
            collapsed.merge(CanonicalKey.from(row), row, Backfill::mergeTransactions);
        }

        long written = 0;
        for (NormalizedTxn canonical : collapsed.values()) {
            target.save(canonical);
            written++;
        }

        long skipped = read - written;
        return new Result(read, written, skipped);
    }

    /** Same deterministic merge used when ingesting into the SQL ledger. */
    static NormalizedTxn mergeTransactions(NormalizedTxn left, NormalizedTxn right) {
        List<String> sourceIds = Stream.concat(
                        left.sourceMessageIds().stream(), right.sourceMessageIds().stream())
                .distinct()
                .sorted()
                .toList();
        NormalizedTxn representative =
                firstSource(left).compareTo(firstSource(right)) <= 0 ? left : right;
        return new NormalizedTxn(representative.accountLast4(), representative.occurredAt(),
                representative.direction(), representative.amount(), representative.category(),
                representative.merchant(), sourceIds);
    }

    private static String firstSource(NormalizedTxn transaction) {
        return transaction.sourceMessageIds().stream().min(String::compareTo).orElseThrow();
    }

    /**
     * @param read    SQL rows examined
     * @param written logical transactions upserted into the document store
     * @param skipped legacy duplicate SQL rows collapsed ({@code read - written})
     */
    public record Result(long read, long written, long skipped) {}
}
