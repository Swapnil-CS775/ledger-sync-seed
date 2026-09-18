package in.simplifymoney.ledgersync.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackfillTest {

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
    void duplicateSqlRowsCollapseIntoOneDocumentWithMergedMessageIds() {
        sql.save(txn("4821", "2026-07-04T13:56:00+05:30", Direction.DEBIT, "2499.50",
                Category.SPEND, "SWIGGY", List.of("m-sms")));
        sql.save(txn("4821", "2026-07-04T13:56:00+05:30", Direction.DEBIT, "2499.50",
                Category.SPEND, "SWIGGY", List.of("m-email")));
        sql.save(txn("9075", "2026-07-01T10:22:00+05:30", Direction.DEBIT, "22.50",
                Category.MICRO, "UPI/X", List.of("m-other")));

        Backfill.Result result = new Backfill(sql, documents).run();

        assertEquals(new Backfill.Result(3, 2, 1), result);
        assertEquals(2, documents.count());
        NormalizedTxn merged = documents.byMessageId("m-sms").orElseThrow();
        assertEquals(List.of("m-email", "m-sms"), merged.sourceMessageIds());
        assertEquals(Optional.of(merged), documents.byMessageId("m-email"));
    }

    @Test
    void backfillIsDeterministicRegardlessOfSqlRowOrder() throws Exception {
        List<NormalizedTxn> rows = List.of(
                txn("4821", "2026-07-04T13:56:00+05:30", Direction.DEBIT, "10.00",
                        Category.MICRO, "UPI/B", List.of("m-b")),
                txn("4821", "2026-07-04T13:56:00+05:30", Direction.DEBIT, "10.00",
                        Category.MICRO, "UPI/A", List.of("m-a")),
                txn("4821", "2026-07-05T10:00:00+05:30", Direction.CREDIT, "5.00",
                        Category.INCOME, "X", List.of("m-c")));

        SqlLedgerStore firstSql = newSql("first");
        rows.forEach(firstSql::save);
        H2DocumentStore firstDocs = H2DocumentStore.inMemory();
        new Backfill(firstSql, firstDocs).run();

        SqlLedgerStore secondSql = newSql("second");
        List<NormalizedTxn> reversed = new ArrayList<>(rows);
        reversed.sort(Comparator.comparing((NormalizedTxn t) -> t.sourceMessageIds().get(0))
                .reversed());
        reversed.forEach(secondSql::save);
        H2DocumentStore secondDocs = H2DocumentStore.inMemory();
        new Backfill(secondSql, secondDocs).run();

        assertEquals(firstDocs.byMessageId("m-a"), secondDocs.byMessageId("m-a"));
        assertEquals(firstDocs.byMessageId("m-b"), secondDocs.byMessageId("m-b"));
        assertEquals(List.of("m-a", "m-b"),
                firstDocs.byMessageId("m-a").orElseThrow().sourceMessageIds());
        assertEquals("UPI/A", firstDocs.byMessageId("m-a").orElseThrow().merchant());

        firstSql.close();
        secondSql.close();
        firstDocs.close();
        secondDocs.close();
    }

    @Test
    void runningBackfillTwiceProducesTheSameDocumentState() {
        sql.save(txn("4821", "2026-07-04T13:56:00+05:30", Direction.DEBIT, "2499.50",
                Category.SPEND, "SWIGGY", List.of("m-sms")));
        sql.save(txn("4821", "2026-07-04T13:56:00+05:30", Direction.DEBIT, "2499.50",
                Category.SPEND, "SWIGGY", List.of("m-email")));

        Backfill backfill = new Backfill(sql, documents);
        Backfill.Result first = backfill.run();
        NormalizedTxn afterFirst = documents.byMessageId("m-sms").orElseThrow();
        long countAfterFirst = documents.count();

        Backfill.Result second = backfill.run();
        NormalizedTxn afterSecond = documents.byMessageId("m-sms").orElseThrow();

        assertEquals(first, second);
        assertEquals(countAfterFirst, documents.count());
        assertEquals(afterFirst, afterSecond);
        assertEquals(List.of("m-email", "m-sms"), afterSecond.sourceMessageIds());
    }

    @Test
    void partialPreExistingDocumentIsSafelyUpsertedWithoutLosingEvidence() {
        NormalizedTxn partial = txn("4821", "2026-07-04T13:56:00+05:30", Direction.DEBIT,
                "2499.50", Category.SPEND, "SWIGGY", List.of("m-sms", "m-extra"));
        documents.save(partial);

        sql.save(txn("4821", "2026-07-04T13:56:00+05:30", Direction.DEBIT, "2499.50",
                Category.SPEND, "SWIGGY", List.of("m-sms")));
        sql.save(txn("4821", "2026-07-04T13:56:00+05:30", Direction.DEBIT, "2499.50",
                Category.SPEND, "SWIGGY", List.of("m-email")));

        Backfill.Result result = new Backfill(sql, documents).run();

        assertEquals(new Backfill.Result(2, 1, 1), result);
        assertEquals(1, documents.count());
        assertEquals(List.of("m-email", "m-extra", "m-sms"),
                documents.byMessageId("m-sms").orElseThrow().sourceMessageIds());
        assertTrue(documents.byMessageId("m-extra").isPresent());
        assertTrue(documents.byMessageId("m-email").isPresent());
    }

    @Test
    void readWrittenSkippedCountsMatchCollapseMath() {
        sql.save(txn("4821", "2026-07-01T09:00:00+05:30", Direction.CREDIT, "1.00",
                Category.INCOME, "A", List.of("m-1")));
        sql.save(txn("4821", "2026-07-01T09:00:00+05:30", Direction.CREDIT, "1.00",
                Category.INCOME, "A", List.of("m-2")));
        sql.save(txn("4821", "2026-07-01T09:00:00+05:30", Direction.CREDIT, "1.00",
                Category.INCOME, "A", List.of("m-3")));
        sql.save(txn("4821", "2026-07-02T09:00:00+05:30", Direction.DEBIT, "2.00",
                Category.SPEND, "B", List.of("m-4")));

        Backfill.Result result = new Backfill(sql, documents).run();

        assertEquals(4, result.read());
        assertEquals(2, result.written());
        assertEquals(2, result.skipped());
        assertEquals(result.read(), result.written() + result.skipped());
        assertEquals(2, documents.forAccountMonth("4821", YearMonth.of(2026, 7)).size());
    }

    private SqlLedgerStore newSql(String name) throws Exception {
        Path migrations = tempDir.resolve("migrations-v1");
        SqlLedgerStore store = new SqlLedgerStore(tempDir.resolve(name));
        store.migrate(migrations);
        return store;
    }

    private static NormalizedTxn txn(String account, String at, Direction direction, String amount,
                                     Category category, String merchant, List<String> sourceIds) {
        return new NormalizedTxn(account, OffsetDateTime.parse(at), direction, new BigDecimal(amount),
                category, merchant, sourceIds);
    }
}
