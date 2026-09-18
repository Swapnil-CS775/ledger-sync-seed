package in.simplifymoney.ledgersync.store;

/**
 * How many documents a DocumentStore query examined versus how many it returned.
 *
 * Populated from the real index/lookup work of each query — not estimated.
 */
public record QueryMetrics(long examined, long returned) {

    public static final QueryMetrics EMPTY = new QueryMetrics(0, 0);
}
