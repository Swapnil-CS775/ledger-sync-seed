package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.report.Reports;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Corpus-level summary and reconciliation checks against the assignment fixture. */
class ReportsCorpusTest {

    @Test
    void summaryAndReconciliationAgainstCorpusA() throws Exception {
        InMemoryLedgerStore store = new InMemoryLedgerStore();
        IngestService.Stats stats = new IngestService(new Parsers(), store)
                .ingestFile(Path.of("fixtures/corpus-a.jsonl"));

        assertEquals(257, stats.transactionsWritten());
        assertEquals(257, store.count());
        assertFalse(stats.balanceObservations().isEmpty());

        Map<String, Object> summary = Reports.summary(store.all());
        Map<String, Object> want = Json.parseObject(
                Files.readString(Path.of("fixtures/corpus-a-totals.json")));
        @SuppressWarnings("unchecked")
        Map<String, Object> wantAccounts = (Map<String, Object>) want.get("accounts");
        @SuppressWarnings("unchecked")
        Map<String, Object> gotAccounts = (Map<String, Object>) summary.get("accounts");

        assertAccountFieldMatches(wantAccounts, gotAccounts, "9075");
        assertEquals(field(wantAccounts, "4821", "income"), field(gotAccounts, "4821", "income"));
        assertEquals(field(wantAccounts, "4821", "micro_total"), field(gotAccounts, "4821", "micro_total"));
        assertEquals(field(wantAccounts, "4821", "transferred_out"),
                field(gotAccounts, "4821", "transferred_out"));
        assertEquals(field(wantAccounts, "4821", "transferred_in"),
                field(gotAccounts, "4821", "transferred_in"));
        assertEquals(number(((Map<?, ?>) wantAccounts.get("4821")).get("micro_count")),
                number(((Map<?, ?>) gotAccounts.get("4821")).get("micro_count")));

        BigDecimal opening4821 = new BigDecimal((String) ((Map<?, ?>) wantAccounts.get("4821"))
                .get("opening_balance"));
        BigDecimal closing4821 = new BigDecimal((String) ((Map<?, ?>) wantAccounts.get("4821"))
                .get("closing_balance"));
        BigDecimal ledgerClose = ledgerBalance(store.all(), "4821", opening4821);
        BigDecimal closingGap = ledgerClose.subtract(closing4821);
        assertTrue(closingGap.compareTo(BigDecimal.ZERO) > 0);

        BigDecimal expectedSpend = new BigDecimal(field(wantAccounts, "4821", "spend"));
        BigDecimal producedSpend = new BigDecimal(field(gotAccounts, "4821", "spend"));
        assertEquals(closingGap, expectedSpend.subtract(producedSpend));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> discrepancies = (List<Map<String, Object>>) Reports
                .reconciliation(store.all(), stats.balanceObservations())
                .get("discrepancies");

        assertFalse(discrepancies.isEmpty());
        assertTrue(discrepancies.stream().allMatch(row -> "4821".equals(row.get("account_last4"))));
        assertTrue(discrepancies.stream().noneMatch(row -> "9075".equals(row.get("account_last4"))));
        assertTrue(discrepancies.stream()
                .allMatch(row -> row.get("note").toString().contains("unexplained")));

        assertEquals(132, store.all().stream().filter(t -> t.category() == Category.SPEND).count());
        assertEquals(Reports.reconciliation(store.all(), stats.balanceObservations()),
                Reports.reconciliation(store.all(), stats.balanceObservations()));
    }

    private static void assertAccountFieldMatches(Map<String, Object> want,
                                                  Map<String, Object> got,
                                                  String account) {
        for (String field : List.of("spend", "income", "micro_total",
                "transferred_out", "transferred_in")) {
            assertEquals(field(want, account, field), field(got, account, field),
                    account + "." + field);
        }
        assertEquals(number(((Map<?, ?>) want.get(account)).get("micro_count")),
                number(((Map<?, ?>) got.get(account)).get("micro_count")),
                account + ".micro_count");
    }

    private static String field(Map<String, Object> accounts, String account, String field) {
        return (String) ((Map<?, ?>) accounts.get(account)).get(field);
    }

    private static int number(Object value) {
        if (value instanceof Number n) return n.intValue();
        return Integer.parseInt(String.valueOf(value));
    }

    private static BigDecimal ledgerBalance(List<NormalizedTxn> ledger, String account,
                                            BigDecimal opening) {
        BigDecimal running = opening;
        for (NormalizedTxn txn : ledger) {
            if (!txn.accountLast4().equals(account)) continue;
            running = txn.direction() == Direction.DEBIT
                    ? running.subtract(txn.amount())
                    : running.add(txn.amount());
        }
        return running;
    }
}
