package in.simplifymoney.ledgersync.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class H2DocumentStoreTest {

    private H2DocumentStore store;

    @BeforeEach
    void open() {
        store = H2DocumentStore.inMemory();
    }

    @AfterEach
    void close() {
        store.close();
    }

    @Test
    void savingTheSameTransactionTwiceDoesNotDuplicateIt() {
        NormalizedTxn txn = txn("4821", "2026-07-04T13:56:00+05:30", Direction.DEBIT,
                "2499.50", Category.SPEND, "SWIGGY", List.of("m-a", "m-b"));

        store.save(txn);
        store.save(txn);

        assertEquals(1, store.count());
        assertEquals(Optional.of(txn), store.byMessageId("m-a"));
        assertEquals(Optional.of(txn), store.byMessageId("m-b"));
    }

    @Test
    void forAccountMonthReturnsNewestFirstAndOnlyThatMonth() {
        NormalizedTxn julyEarly = txn("4821", "2026-07-01T09:02:00+05:30", Direction.CREDIT,
                "45000.00", Category.INCOME, "SALARY", List.of("m-july-1"));
        NormalizedTxn julyLate = txn("4821", "2026-07-21T13:14:00+05:30", Direction.DEBIT,
                "12000.00", Category.TRANSFER, "OWN", List.of("m-july-2"));
        NormalizedTxn august = txn("4821", "2026-08-01T09:02:00+05:30", Direction.CREDIT,
                "45000.00", Category.INCOME, "SALARY", List.of("m-aug-1"));
        NormalizedTxn otherAccount = txn("9075", "2026-07-05T11:02:00+05:30", Direction.CREDIT,
                "8000.00", Category.TRANSFER, "OWN", List.of("m-other"));

        store.save(julyEarly);
        store.save(julyLate);
        store.save(august);
        store.save(otherAccount);

        List<NormalizedTxn> july = store.forAccountMonth("4821", YearMonth.of(2026, 7));

        assertEquals(List.of(julyLate, julyEarly), july);
        assertEquals(new QueryMetrics(2, 2), store.lastMetrics());
    }

    @Test
    void categoryTotalsReflectWholeAccountHistory() {
        store.save(txn("4821", "2026-07-01T09:02:00+05:30", Direction.CREDIT,
                "100.00", Category.INCOME, "A", List.of("m-1")));
        store.save(txn("4821", "2026-07-02T10:00:00+05:30", Direction.DEBIT,
                "40.00", Category.MICRO, "UPI/X", List.of("m-2")));
        store.save(txn("4821", "2026-08-01T10:00:00+05:30", Direction.DEBIT,
                "25.00", Category.SPEND, "SHOP", List.of("m-3")));
        store.save(txn("4821", "2026-08-02T10:00:00+05:30", Direction.DEBIT,
                "10.00", Category.TRANSFER, "OWN", List.of("m-4")));
        store.save(txn("9075", "2026-07-01T10:00:00+05:30", Direction.CREDIT,
                "999.00", Category.INCOME, "OTHER", List.of("m-5")));

        Map<Category, BigDecimal> totals = store.categoryTotals("4821");

        assertEquals(new BigDecimal("25.00"), totals.get(Category.SPEND));
        assertEquals(new BigDecimal("100.00"), totals.get(Category.INCOME));
        assertEquals(new BigDecimal("40.00"), totals.get(Category.MICRO));
        assertEquals(new BigDecimal("10.00"), totals.get(Category.TRANSFER));
        assertEquals(new QueryMetrics(1, 1), store.lastMetrics());
    }

    @Test
    void byMessageIdFindsTheTransactionAndMultipleIdsMapToTheSameDocument() {
        NormalizedTxn txn = txn("4821", "2026-07-01T09:02:00+05:30", Direction.CREDIT,
                "45000.00", Category.INCOME, "SALARY", List.of("m-sms", "m-email"));

        store.save(txn);

        assertEquals(Optional.of(txn), store.byMessageId("m-sms"));
        assertEquals(new QueryMetrics(1, 1), store.lastMetrics());
        assertEquals(Optional.of(txn), store.byMessageId("m-email"));
        assertEquals(Optional.empty(), store.byMessageId("m-missing"));
        assertEquals(new QueryMetrics(0, 0), store.lastMetrics());
        assertEquals(1, store.count());
    }

    @Test
    void reSaveWithAdditionalMessageIdsStaysSingleDocumentAndUpdatesLookup() {
        NormalizedTxn first = txn("4821", "2026-07-04T13:56:00+05:30", Direction.DEBIT,
                "2499.50", Category.SPEND, "SWIGGY", List.of("m-sms"));
        NormalizedTxn merged = txn("4821", "2026-07-04T13:56:00+05:30", Direction.DEBIT,
                "2499.50", Category.SPEND, "SWIGGY", List.of("m-email", "m-sms"));

        store.save(first);
        store.save(merged);

        assertEquals(1, store.count());
        assertEquals(Optional.of(merged), store.byMessageId("m-sms"));
        assertEquals(Optional.of(merged), store.byMessageId("m-email"));
        assertEquals(new BigDecimal("2499.50"), store.categoryTotals("4821").get(Category.SPEND));
    }

    @Test
    void queryMetricsArePopulatedForEachAccessPattern() {
        store.save(txn("4821", "2026-07-04T13:56:00+05:30", Direction.DEBIT,
                "10.00", Category.MICRO, "UPI/X", List.of("m-1")));

        store.forAccountMonth("4821", YearMonth.of(2026, 7));
        assertTrue(store.lastMetrics().examined() >= store.lastMetrics().returned());
        assertEquals(1, store.lastMetrics().returned());

        store.categoryTotals("4821");
        assertEquals(1, store.lastMetrics().examined());
        assertEquals(1, store.lastMetrics().returned());

        store.byMessageId("m-1");
        assertEquals(1, store.lastMetrics().examined());
        assertEquals(1, store.lastMetrics().returned());
    }

    private static NormalizedTxn txn(String account, String at, Direction direction, String amount,
                                     Category category, String merchant, List<String> sourceIds) {
        return new NormalizedTxn(account, OffsetDateTime.parse(at), direction, new BigDecimal(amount),
                category, merchant, sourceIds);
    }
}
