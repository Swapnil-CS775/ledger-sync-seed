package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.ingest.CanonicalKey;
import in.simplifymoney.ledgersync.model.NormalizedTxn;

/**
 * Deterministic document identity for a NormalizedTxn.
 *
 * Uses the same CanonicalKey already used by ingest deduplication so a logical
 * transaction maps to exactly one document.
 */
public final class TxnDocumentId {

    private TxnDocumentId() {}

    public static String of(NormalizedTxn txn) {
        return of(CanonicalKey.from(txn));
    }

    public static String of(CanonicalKey key) {
        return key.accountLast4()
                + '|' + key.occurredAt()
                + '|' + key.direction().name()
                + '|' + key.amount().toPlainString();
    }
}
