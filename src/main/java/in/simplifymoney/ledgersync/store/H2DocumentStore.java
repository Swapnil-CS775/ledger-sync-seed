package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Document-store-shaped ledger on H2 (already a project dependency).
 *
 * <h2>Document schema</h2>
 *
 * One transaction document per canonical movement, keyed by
 * {@link TxnDocumentId} (= {@code CanonicalKey}):
 *
 * <pre>
 * {
 *   "document_id": "4821|2026-07-04T13:56+05:30|DEBIT|2499.50",
 *   "account_last4": "4821",
 *   "year_month": "2026-07",
 *   "occurred_at": "2026-07-04T13:56+05:30",
 *   "direction": "debit",
 *   "amount": "2499.50",
 *   "category": "SPEND",
 *   "merchant": "SWIGGY",
 *   "source_message_ids": ["m-00025-aa9fa5", "m-00026-..."]
 * }
 * </pre>
 *
 * Access patterns are served directly:
 * <ul>
 *   <li>Q1 — index {@code (account_last4, year_month, occurred_at DESC)}</li>
 *   <li>Q2 — one {@code account_category_totals} row per account, updated on save</li>
 *   <li>Q3 — {@code message_lookup} primary key on {@code message_id}</li>
 * </ul>
 *
 * Saving is an upsert on {@code document_id}, so the same logical transaction
 * never creates a second document.
 */
public final class H2DocumentStore implements DocumentStore, AutoCloseable {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final Connection conn;
    private volatile QueryMetrics lastMetrics = QueryMetrics.EMPTY;

    public H2DocumentStore(Path dbFile) {
        this(urlForFile(dbFile));
    }

    /** In-memory store for tests. */
    public H2DocumentStore(String jdbcUrl) {
        try {
            this.conn = DriverManager.getConnection(jdbcUrl, "sa", "");
            migrate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not open document store at " + jdbcUrl, e);
        }
    }

    private static String urlForFile(Path dbFile) {
        return "jdbc:h2:" + dbFile.toAbsolutePath() + ";MODE=PostgreSQL";
    }

    public static H2DocumentStore inMemory() {
        return new H2DocumentStore("jdbc:h2:mem:docs-" + System.nanoTime() + ";MODE=PostgreSQL");
    }

    /** Metrics from the most recent Q1/Q2/Q3 call. */
    public QueryMetrics lastMetrics() {
        return lastMetrics;
    }

    private void migrate() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS txn_documents (
                      document_id     VARCHAR(200) PRIMARY KEY,
                      account_last4   VARCHAR(4)     NOT NULL,
                      year_month      VARCHAR(7)     NOT NULL,
                      occurred_at     VARCHAR(40)    NOT NULL,
                      direction       VARCHAR(6)     NOT NULL,
                      amount          DECIMAL(14, 2) NOT NULL,
                      category        VARCHAR(10)    NOT NULL,
                      merchant        VARCHAR(120),
                      document_json   CLOB           NOT NULL
                    )
                    """);
            st.execute("""
                    CREATE INDEX IF NOT EXISTS idx_txn_account_month_time
                      ON txn_documents (account_last4, year_month, occurred_at DESC)
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS message_lookup (
                      message_id   VARCHAR(80) PRIMARY KEY,
                      document_id  VARCHAR(200) NOT NULL
                    )
                    """);
            st.execute("""
                    CREATE INDEX IF NOT EXISTS idx_message_document
                      ON message_lookup (document_id)
                    """);

            st.execute("""
                    CREATE TABLE IF NOT EXISTS account_category_totals (
                      account_last4  VARCHAR(4) PRIMARY KEY,
                      spend          DECIMAL(14, 2) NOT NULL,
                      income         DECIMAL(14, 2) NOT NULL,
                      micro          DECIMAL(14, 2) NOT NULL,
                      transfer       DECIMAL(14, 2) NOT NULL
                    )
                    """);
        }
    }

    @Override
    public synchronized void save(NormalizedTxn txn) {
        String documentId = TxnDocumentId.of(txn);
        try {
            conn.setAutoCommit(false);
            Optional<NormalizedTxn> existing = readByDocumentId(documentId);

NormalizedTxn next = existing
        .map(current -> Backfill.mergeTransactions(current, txn))
        .orElse(txn);

upsertDocument(documentId, next);
replaceMessageLookup(documentId, next.sourceMessageIds());

adjustTotals(next.accountLast4(), existing.orElse(null), next);
            conn.commit();
        } catch (SQLException e) {
            try { conn.rollback(); } catch (SQLException ignored) { }
            throw new IllegalStateException("could not save document " + documentId, e);
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignored) { }
        }
    }

    @Override
    public List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        String yearMonth = month.toString();
        List<NormalizedTxn> out = new ArrayList<>();
        long examined = 0;
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT document_json FROM txn_documents
                 WHERE account_last4 = ? AND year_month = ?
                 ORDER BY occurred_at DESC, document_id DESC
                """)) {
            ps.setString(1, accountLast4);
            ps.setString(2, yearMonth);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    examined++;
                    out.add(fromJson(rs.getString(1)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Q1 forAccountMonth failed", e);
        }
        lastMetrics = new QueryMetrics(examined, out.size());
        return List.copyOf(out);
    }

    @Override
    public Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        Map<Category, BigDecimal> totals = emptyTotals();
        long examined = 0;
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT spend, income, micro, transfer
                  FROM account_category_totals
                 WHERE account_last4 = ?
                """)) {
            ps.setString(1, accountLast4);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    examined = 1;
                    totals.put(Category.SPEND, scale(rs.getBigDecimal(1)));
                    totals.put(Category.INCOME, scale(rs.getBigDecimal(2)));
                    totals.put(Category.MICRO, scale(rs.getBigDecimal(3)));
                    totals.put(Category.TRANSFER, scale(rs.getBigDecimal(4)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Q2 categoryTotals failed", e);
        }
        lastMetrics = new QueryMetrics(examined, 1);
        return totals;
    }

    @Override
    public Optional<NormalizedTxn> byMessageId(String messageId) {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT d.document_json
                  FROM message_lookup m
                  JOIN txn_documents d ON d.document_id = m.document_id
                 WHERE m.message_id = ?
                """)) {
            ps.setString(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    lastMetrics = new QueryMetrics(0, 0);
                    return Optional.empty();
                }
                // Point lookup via message_id PK + one parent document fetch.
                lastMetrics = new QueryMetrics(1, 1);
                return Optional.of(fromJson(rs.getString(1)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Q3 byMessageId failed", e);
        }
    }

    /** Number of transaction documents currently stored. */
    public long count() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM txn_documents")) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("could not count documents", e);
        }
    }

    /**
     * Package-private audit method: enumerate all transaction documents in deterministic order.
     * Used by ConsistencyChecker to verify store consistency.
     */
    List<NormalizedTxn> allDocumentsForAudit() {
        List<NormalizedTxn> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT document_json FROM txn_documents ORDER BY document_id")) {
            while (rs.next()) {
                out.add(fromJson(rs.getString(1)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not audit documents", e);
        }
        return List.copyOf(out);
    }

    private void upsertDocument(String documentId, NormalizedTxn txn) throws SQLException {
        String yearMonth = YearMonth.from(txn.occurredAt()).toString();
        String json = toJson(documentId, txn, yearMonth);
        try (PreparedStatement ps = conn.prepareStatement("""
                MERGE INTO txn_documents
                  (document_id, account_last4, year_month, occurred_at, direction,
                   amount, category, merchant, document_json)
                KEY (document_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, documentId);
            ps.setString(2, txn.accountLast4());
            ps.setString(3, yearMonth);
            ps.setString(4, txn.occurredAt().toString());
            ps.setString(5, txn.direction().name());
            ps.setBigDecimal(6, txn.amount());
            ps.setString(7, txn.category().name());
            ps.setString(8, txn.merchant());
            ps.setString(9, json);
            ps.executeUpdate();
        }
    }

    private void replaceMessageLookup(String documentId, List<String> messageIds) throws SQLException {
        try (PreparedStatement del = conn.prepareStatement(
                "DELETE FROM message_lookup WHERE document_id = ?")) {
            del.setString(1, documentId);
            del.executeUpdate();
        }
        try (PreparedStatement ins = conn.prepareStatement("""
                MERGE INTO message_lookup (message_id, document_id)
                KEY (message_id)
                VALUES (?, ?)
                """)) {
            for (String messageId : messageIds) {
                ins.setString(1, messageId);
                ins.setString(2, documentId);
                ins.addBatch();
            }
            ins.executeBatch();
        }
    }

    private void adjustTotals(String account, NormalizedTxn previous, NormalizedTxn next)
            throws SQLException {
        Map<Category, BigDecimal> totals = loadTotals(account);
        if (previous != null) {
            totals.put(previous.category(),
                    totals.get(previous.category()).subtract(previous.amount()));
        }
        totals.put(next.category(), totals.get(next.category()).add(next.amount()));
        try (PreparedStatement ps = conn.prepareStatement("""
                MERGE INTO account_category_totals
                  (account_last4, spend, income, micro, transfer)
                KEY (account_last4)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, account);
            ps.setBigDecimal(2, totals.get(Category.SPEND));
            ps.setBigDecimal(3, totals.get(Category.INCOME));
            ps.setBigDecimal(4, totals.get(Category.MICRO));
            ps.setBigDecimal(5, totals.get(Category.TRANSFER));
            ps.executeUpdate();
        }
    }

    private Map<Category, BigDecimal> loadTotals(String account) throws SQLException {
        Map<Category, BigDecimal> totals = emptyTotals();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT spend, income, micro, transfer
                  FROM account_category_totals WHERE account_last4 = ?
                """)) {
            ps.setString(1, account);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    totals.put(Category.SPEND, scale(rs.getBigDecimal(1)));
                    totals.put(Category.INCOME, scale(rs.getBigDecimal(2)));
                    totals.put(Category.MICRO, scale(rs.getBigDecimal(3)));
                    totals.put(Category.TRANSFER, scale(rs.getBigDecimal(4)));
                }
            }
        }
        return totals;
    }

    private Optional<NormalizedTxn> readByDocumentId(String documentId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT document_json FROM txn_documents WHERE document_id = ?")) {
            ps.setString(1, documentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(fromJson(rs.getString(1)));
            }
        }
    }

    private static Map<Category, BigDecimal> emptyTotals() {
        Map<Category, BigDecimal> totals = new EnumMap<>(Category.class);
        for (Category category : Category.values()) totals.put(category, ZERO);
        return totals;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2);
    }

    private static String toJson(String documentId, NormalizedTxn txn, String yearMonth) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("document_id", documentId);
        doc.put("account_last4", txn.accountLast4());
        doc.put("year_month", yearMonth);
        doc.put("occurred_at", txn.occurredAt().toString());
        doc.put("direction", txn.direction().name().toLowerCase());
        doc.put("amount", txn.amount().toPlainString());
        doc.put("category", txn.category().name());
        doc.put("merchant", txn.merchant());
        doc.put("source_message_ids", txn.sourceMessageIds());
        return Json.write(doc);
    }

    @SuppressWarnings("unchecked")
    private static NormalizedTxn fromJson(String json) {
        Map<String, Object> doc = Json.parseObject(json);
        List<String> sourceIds = ((List<Object>) doc.get("source_message_ids")).stream()
                .map(String::valueOf)
                .toList();
        return new NormalizedTxn(
                (String) doc.get("account_last4"),
                OffsetDateTime.parse((String) doc.get("occurred_at")),
                Direction.valueOf(((String) doc.get("direction")).toUpperCase()),
                new BigDecimal((String) doc.get("amount")),
                Category.valueOf((String) doc.get("category")),
                (String) doc.get("merchant"),
                sourceIds);
    }

    @Override
    public void close() {
        try { conn.close(); } catch (SQLException ignored) { }
    }
}
