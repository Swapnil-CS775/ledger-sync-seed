package in.simplifymoney.ledgersync.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportsTest {

    @Test
    void summaryTotalsByCategoryAndExcludesMicroAndTransferFromSpendIncome() {
        List<NormalizedTxn> ledger = List.of(
                txn("4821", "2026-07-01T10:00:00+05:30", Direction.DEBIT, "500.00", Category.SPEND, "SHOP"),
                txn("4821", "2026-07-01T11:00:00+05:30", Direction.DEBIT, "40.00", Category.MICRO, "UPI/TEA"),
                txn("4821", "2026-07-01T12:00:00+05:30", Direction.CREDIT, "1000.00", Category.INCOME, "SALARY"),
                txn("4821", "2026-07-01T13:00:00+05:30", Direction.DEBIT, "200.00", Category.TRANSFER, "OWN"),
                txn("4821", "2026-07-01T14:00:00+05:30", Direction.CREDIT, "50.00", Category.TRANSFER, "OWN"),
                txn("9075", "2026-07-01T13:00:00+05:30", Direction.CREDIT, "200.00", Category.TRANSFER, "OWN"));

        @SuppressWarnings("unchecked")
        Map<String, Object> accounts = (Map<String, Object>) Reports.summary(ledger).get("accounts");
        @SuppressWarnings("unchecked")
        Map<String, Object> a4821 = (Map<String, Object>) accounts.get("4821");

        assertEquals("500.00", a4821.get("spend"));
        assertEquals("1000.00", a4821.get("income"));
        assertEquals(1, a4821.get("micro_count"));
        assertEquals("40.00", a4821.get("micro_total"));
        assertEquals("200.00", a4821.get("transferred_out"));
        assertEquals("50.00", a4821.get("transferred_in"));
    }

    @Test
    void unexplainedBalanceDifferenceIsReportedNotFabricatedAway() {
        List<NormalizedTxn> ledger = List.of(
                txn("4821", "2026-07-29T11:53:00+05:30", Direction.DEBIT, "899.99", Category.SPEND, "IRCTC"),
                txn("4821", "2026-07-29T17:06:00+05:30", Direction.DEBIT, "75.00", Category.MICRO, "UPI/STATIONERY"));

        List<BalanceObservation> observations = List.of(
                obs("4821", "2026-07-29T11:53:00+05:30", Direction.DEBIT, "899.99", "36054.05", "m-prev"),
                obs("4821", "2026-07-29T17:06:00+05:30", Direction.DEBIT, "75.00", "28479.05", "m-curr"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> discrepancies =
                (List<Map<String, Object>>) Reports.reconciliation(ledger, observations).get("discrepancies");

        assertEquals(1, discrepancies.size());
        assertEquals("4821", discrepancies.get(0).get("account_last4"));
        assertEquals("2026-07-29T17:06+05:30", discrepancies.get(0).get("occurred_at"));
        assertEquals("7500.00", discrepancies.get(0).get("amount"));
        assertTrue(discrepancies.get(0).get("note").toString().contains("unexplained"));
    }

    @Test
    void explainedObservationChainProducesNoDiscrepancy() {
        List<NormalizedTxn> ledger = List.of(
                txn("9075", "2026-07-01T10:22:00+05:30", Direction.DEBIT, "22.50", Category.MICRO, "UPI/VEGETABLE VENDOR"),
                txn("9075", "2026-07-01T21:14:00+05:30", Direction.CREDIT, "18000.00", Category.INCOME, "NEFT INWARD SELF"));

        List<BalanceObservation> observations = List.of(
                obs("9075", "2026-07-01T10:22:00+05:30", Direction.DEBIT, "22.50", "31882.25", "m-1"),
                obs("9075", "2026-07-01T21:14:00+05:30", Direction.CREDIT, "18000.00", "49882.25", "m-2"));

        @SuppressWarnings("unchecked")
        List<?> discrepancies = (List<?>) Reports.reconciliation(ledger, observations).get("discrepancies");
        assertEquals(List.of(), discrepancies);
    }

    @Test
    void repeatedReconciliationIsDeterministic() {
        List<NormalizedTxn> ledger = List.of(
                txn("4821", "2026-07-29T11:53:00+05:30", Direction.DEBIT, "899.99", Category.SPEND, "IRCTC"),
                txn("4821", "2026-07-29T17:06:00+05:30", Direction.DEBIT, "75.00", Category.MICRO, "UPI/STATIONERY"));
        List<BalanceObservation> observations = List.of(
                obs("4821", "2026-07-29T17:06:00+05:30", Direction.DEBIT, "75.00", "28479.05", "m-curr"),
                obs("4821", "2026-07-29T11:53:00+05:30", Direction.DEBIT, "899.99", "36054.05", "m-prev"));

        Map<String, Object> first = Reports.reconciliation(ledger, observations);
        Map<String, Object> second = Reports.reconciliation(ledger, observations);
        assertEquals(first, second);
    }

    @Test
    void duplicateBalanceObservationsAreDeduped() {
        List<NormalizedTxn> ledger = List.of(
                txn("4821", "2026-07-01T10:00:00+05:30", Direction.DEBIT, "10.00", Category.MICRO, "UPI/X"),
                txn("4821", "2026-07-01T11:00:00+05:30", Direction.DEBIT, "20.00", Category.MICRO, "UPI/Y"));
        List<BalanceObservation> observations = List.of(
                obs("4821", "2026-07-01T10:00:00+05:30", Direction.DEBIT, "10.00", "100.00", "m-a"),
                obs("4821", "2026-07-01T10:00:00+05:30", Direction.DEBIT, "10.00", "100.00", "m-a-dup"),
                obs("4821", "2026-07-01T11:00:00+05:30", Direction.DEBIT, "20.00", "80.00", "m-b"));

        @SuppressWarnings("unchecked")
        List<?> discrepancies = (List<?>) Reports.reconciliation(ledger, observations).get("discrepancies");
        assertEquals(List.of(), discrepancies);
    }

    private static NormalizedTxn txn(String account, String at, Direction direction, String amount,
                                     Category category, String merchant) {
        return new NormalizedTxn(account, OffsetDateTime.parse(at), direction, new BigDecimal(amount),
                category, merchant, List.of("m-" + account + "-" + amount));
    }

    private static BalanceObservation obs(String account, String at, Direction direction, String amount,
                                          String stated, String messageId) {
        return new BalanceObservation(account, OffsetDateTime.parse(at), direction,
                new BigDecimal(amount), new BigDecimal(stated), messageId);
    }
}
