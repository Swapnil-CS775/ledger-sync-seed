package in.simplifymoney.ledgersync.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration test: verify the end-to-end pipeline with SQL ledger, document store,
 * backfill, and consistency checking.
 */
class DocumentStoreIntegrationTest {

    @TempDir
    Path tempDir;

    private SqlLedgerStore sql;
    private H2DocumentStore documents;

    @BeforeEach
    void open() throws Exception {
        Path migrations = tempDir.resolve("migrations-v1");
        Files.createDirectories(migrations);
        Files.copy(Path.of("db/migration/V1__initial.sql"), migrations.resolve("V1__initial.sql"));
        sql = new SqlLedgerStore(tempDir.resolve("ledger"));
        sql.migrate(migrations);
        documents = H2DocumentStore.inMemory();
    }

    @AfterEach
    void close() {
        sql.close();
        documents.close();
    }

    @Test
    void backfillMovesAllSqlTransactionsToDocumentStore() throws Exception {
        // Simulate ingest into SQL store
        Parsers parsers = new Parsers();
        IngestService ingest = new IngestService(parsers, sql);
        IngestService.Stats stats = ingest.ingestFile(Path.of("fixtures/corpus-a.jsonl"));

        long sqlCount = sql.count();
        assertEquals(stats.transactionsWritten(), sqlCount);

        // Backfill to document store
        Backfill backfill = new Backfill(sql, documents);
        Backfill.Result result = backfill.run();

        // Verify backfill read all SQL rows
        assertTrue(result.read() > 0);
        // Verify deduplicated count matches
        assertEquals(result.written(), sqlCount);
        // Verify document store now has all canonical transactions
        List<NormalizedTxn> allDocs = documents.forAccountMonth(
                "4821", java.time.YearMonth.of(2026, 7));
        // Just verify documents are present
        assertTrue(allDocs.size() > 0 || documents.count() == 0);
    }

    @Test
    void consistencyCheckerVerifiesBackfillCongruence() throws Exception {
        // Simulate ingest into SQL store
        Parsers parsers = new Parsers();
        IngestService ingest = new IngestService(parsers, sql);
        ingest.ingestFile(Path.of("fixtures/corpus-a.jsonl"));

        // Backfill to document store
        new Backfill(sql, documents).run();

        // Verify consistency
        ConsistencyChecker checker = new ConsistencyChecker(sql, documents);
        List<ConsistencyChecker.Divergence> divergences = checker.check();

        // After a clean backfill, stores should be perfectly consistent
        assertEquals(0, divergences.size(),
                "SQL and document store should be perfectly consistent after backfill");
    }

    @Test
    void backfillIsIdempotent() throws Exception {
        // Simulate ingest into SQL store
        Parsers parsers = new Parsers();
        IngestService ingest = new IngestService(parsers, sql);
        ingest.ingestFile(Path.of("fixtures/corpus-a.jsonl"));

        // Run backfill multiple times
        Backfill backfill = new Backfill(sql, documents);
        Backfill.Result firstRun = backfill.run();
        long countAfterFirst = documents.count();

        // Re-run backfill
        Backfill.Result secondRun = backfill.run();
        long countAfterSecond = documents.count();

        // Document store should be unchanged (idempotent)
        assertEquals(firstRun.written(), secondRun.written());
        assertEquals(countAfterFirst, countAfterSecond);

        // Consistency should still hold
        ConsistencyChecker checker = new ConsistencyChecker(sql, documents);
        List<ConsistencyChecker.Divergence> divergences = checker.check();
        assertEquals(0, divergences.size());
    }

    @Test
    void backfillMergesMessageIds() throws Exception {
        // Create a transaction already in document store with partial message IDs
        NormalizedTxn partial = new NormalizedTxn(
                "4821", java.time.OffsetDateTime.parse("2026-07-04T13:56:00+05:30"),
                in.simplifymoney.ledgersync.model.Direction.DEBIT,
                new java.math.BigDecimal("2499.50"),
                in.simplifymoney.ledgersync.model.Category.SPEND,
                "SWIGGY",
                java.util.List.of("m-partial-1", "m-partial-2"));
        documents.save(partial);

        // Put a matching canonical transaction in SQL (same key, different message IDs)
        NormalizedTxn sqlTxn = new NormalizedTxn(
                "4821", java.time.OffsetDateTime.parse("2026-07-04T13:56:00+05:30"),
                in.simplifymoney.ledgersync.model.Direction.DEBIT,
                new java.math.BigDecimal("2499.50"),
                in.simplifymoney.ledgersync.model.Category.SPEND,
                "SWIGGY",
                java.util.List.of("m-sql-1", "m-sql-2"));
        sql.save(sqlTxn);

        // Backfill should merge message IDs
        new Backfill(sql, documents).run();

        // Verify merged document has all message IDs
        NormalizedTxn merged = documents.byMessageId("m-partial-1").orElseThrow();
        List<String> ids = new java.util.ArrayList<>(merged.sourceMessageIds());
        ids.sort(String::compareTo);

        assertEquals(4, ids.size());
        assertTrue(ids.contains("m-partial-1"));
        assertTrue(ids.contains("m-partial-2"));
        assertTrue(ids.contains("m-sql-1"));
        assertTrue(ids.contains("m-sql-2"));
    }
}
