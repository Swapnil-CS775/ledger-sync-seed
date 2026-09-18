package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.ingest.CanonicalKey;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Proves the two stores agree, and says precisely where they do not.
 *
 * NOT IMPLEMENTED - this is yours.
 *
 * We will run your checker against a document store we have deliberately
 * altered. It has to find what we changed and name it. A checker that only
 * compares row counts will not.
 */
public final class ConsistencyChecker {

    private final SqlLedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(SqlLedgerStore sql, DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    public List<Divergence> check() {
        List<Divergence> divergences = new ArrayList<>();

        // Get all transactions from both stores
        List<NormalizedTxn> sqlTxns = sql.all();
        List<NormalizedTxn> docTxns = getAllDocuments();

        // Build a map of canonical keys to SQL transactions for quick lookup
        Map<CanonicalKey, NormalizedTxn> sqlByKey = sqlTxns.stream()
                .collect(Collectors.toMap(CanonicalKey::from, txn -> txn));

        // Build a set of keys present in documents for tracking
        Set<CanonicalKey> docKeys = docTxns.stream()
                .map(CanonicalKey::from)
                .collect(Collectors.toSet());

        // Check 1: SQL → DocumentStore (missing documents)
        for (NormalizedTxn sqlTxn : sqlTxns) {
            CanonicalKey key = CanonicalKey.from(sqlTxn);
            if (!docKeys.contains(key)) {
                divergences.add(new Divergence(
                        "MISSING_DOCUMENT",
                        TxnDocumentId.of(key),
                        null));
            }
        }

        // Check 2: DocumentStore → SQL (extra documents)
        Set<CanonicalKey> sqlKeys = new HashSet<>(sqlByKey.keySet());
        for (NormalizedTxn docTxn : docTxns) {
            CanonicalKey key = CanonicalKey.from(docTxn);
            if (!sqlKeys.contains(key)) {
                divergences.add(new Divergence(
                        "EXTRA_DOCUMENT",
                        null,
                        TxnDocumentId.of(key)));
            }
        }

        // Check 3: Field-exact comparison for transactions that exist in both stores
        for (NormalizedTxn docTxn : docTxns) {
            CanonicalKey key = CanonicalKey.from(docTxn);
            NormalizedTxn sqlTxn = sqlByKey.get(key);
            if (sqlTxn != null) {
                // Compare each field
                compareField("account_last4", sqlTxn.accountLast4(), docTxn.accountLast4(),
                        sqlTxn, docTxn, divergences);
                compareField("occurred_at", sqlTxn.occurredAt().toString(),
                        docTxn.occurredAt().toString(), sqlTxn, docTxn, divergences);
                compareField("direction", sqlTxn.direction().name(),
                        docTxn.direction().name(), sqlTxn, docTxn, divergences);
                compareField("amount", sqlTxn.amount().toPlainString(),
                        docTxn.amount().toPlainString(), sqlTxn, docTxn, divergences);
                compareField("category", sqlTxn.category().name(),
                        docTxn.category().name(), sqlTxn, docTxn, divergences);
                compareField("merchant", sqlTxn.merchant(), docTxn.merchant(),
                        sqlTxn, docTxn, divergences);

                // Compare source_message_ids as sorted canonical sets
                List<String> sqlIds = new ArrayList<>(sqlTxn.sourceMessageIds());
                sqlIds.sort(String::compareTo);
                List<String> docIds = new ArrayList<>(docTxn.sourceMessageIds());
                docIds.sort(String::compareTo);
                compareField("source_message_ids", String.join(",", sqlIds),
                        String.join(",", docIds), sqlTxn, docTxn, divergences);
            }
        }

        // Sort divergences deterministically
        divergences.sort(Comparator.comparing(d -> {
            String key = d.inSql() != null ? d.inSql() : (d.inDocuments() != null ? d.inDocuments() : "");
            return key;
        }));

        return divergences;
    }

    private void compareField(String fieldName, String sqlValue, String docValue,
                              NormalizedTxn sqlTxn, NormalizedTxn docTxn,
                              List<Divergence> divergences) {
        if (!sqlValue.equals(docValue)) {
            String txnId = TxnDocumentId.of(sqlTxn);
            divergences.add(new Divergence(
                    "MUTATED_DOCUMENT:" + fieldName,
                    txnId + "=" + sqlValue,
                    txnId + "=" + docValue));
        }
    }

    private List<NormalizedTxn> getAllDocuments() {
        // Cast to H2DocumentStore to access the audit method
        if (documents instanceof H2DocumentStore) {
            return ((H2DocumentStore) documents).allDocumentsForAudit();
        }
        // Fallback: this shouldn't happen in normal use, but we need a way to enumerate
        // For now, throw to ensure it's only used with H2DocumentStore
        throw new IllegalStateException("ConsistencyChecker requires H2DocumentStore for full enumeration");
    }

    /** One place the two stores disagree. */
    public record Divergence(String what, String inSql, String inDocuments) {}
}
