package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Benchmark to measure H2DocumentStore query performance at scale (100,000 transactions).
 *
 * Generates realistic transaction data and measures actual examined vs returned counts
 * for Q1/Q2/Q3 query patterns. Results are printed to stdout for documentation.
 */
class H2DocumentStoreBenchmark {

    @Test
    void measureQueryMetricsAt100kTransactions() {
        H2DocumentStore store = H2DocumentStore.inMemory();
        Random rand = new Random(42); // Deterministic seed

        System.out.println("\n=== H2DocumentStore Benchmark: 100,000 Transactions ===\n");

        // Generate and save 100,000 transactions
        System.out.println("Generating 100,000 transactions...");
        List<NormalizedTxn> allTxns = generateTransactions(100_000, rand);
        System.out.println("Saving to H2DocumentStore...");
        for (NormalizedTxn txn : allTxns) {
            store.save(txn);
        }
        System.out.println("Saved " + store.count() + " transactions.\n");

        // Distribute transactions across accounts
        List<NormalizedTxn> account4821 = allTxns.stream()
                .filter(t -> t.accountLast4().equals("4821")).toList();
        List<NormalizedTxn> account9075 = allTxns.stream()
                .filter(t -> t.accountLast4().equals("9075")).toList();
        List<NormalizedTxn> account3310 = allTxns.stream()
                .filter(t -> t.accountLast4().equals("3310")).toList();

        System.out.println("Account distribution:");
        System.out.println("  4821: " + account4821.size() + " transactions");
        System.out.println("  9075: " + account9075.size() + " transactions");
        System.out.println("  3310: " + account3310.size() + " transactions\n");

        // Q1: forAccountMonth - query the busiest month for the largest account
        System.out.println("--- Q1: forAccountMonth ---");
        YearMonth busiestMonth = findBusiestMonth(account4821);
        System.out.println("Querying account 4821 for " + busiestMonth + " (busiest month)");
        store.forAccountMonth("4821", busiestMonth);
        QueryMetrics q1Metrics = store.lastMetrics();
        System.out.println("  Examined: " + q1Metrics.examined());
        System.out.println("  Returned: " + q1Metrics.returned());
        System.out.println();

        // Q2: categoryTotals - query a busy account
        System.out.println("--- Q2: categoryTotals ---");
        System.out.println("Querying category totals for account 4821");
        store.categoryTotals("4821");
        QueryMetrics q2Metrics = store.lastMetrics();
        System.out.println("  Examined: " + q2Metrics.examined());
        System.out.println("  Returned: " + q2Metrics.returned());
        System.out.println();

        // Q3: byMessageId - query a specific message
        System.out.println("--- Q3: byMessageId ---");
        String testMessageId = account4821.get(0).sourceMessageIds().get(0);
        System.out.println("Querying message ID: " + testMessageId);
        store.byMessageId(testMessageId);
        QueryMetrics q3Metrics = store.lastMetrics();
        System.out.println("  Examined: " + q3Metrics.examined());
        System.out.println("  Returned: " + q3Metrics.returned());
        System.out.println();

        // Summary table
        System.out.println("=== MEASURED METRICS FOR 100,000 TRANSACTIONS ===");
        System.out.println();
        System.out.println("| Query | Examined | Returned |");
        System.out.println("|-------|----------|----------|");
        System.out.printf("| Q1: forAccountMonth | %d | %d |\n", q1Metrics.examined(), q1Metrics.returned());
        System.out.printf("| Q2: categoryTotals | %d | %d |\n", q2Metrics.examined(), q2Metrics.returned());
        System.out.printf("| Q3: byMessageId | %d | %d |\n", q3Metrics.examined(), q3Metrics.returned());
        System.out.println();

        store.close();
    }

    /**
     * Generate 100,000 realistic NormalizedTxn records.
     *
     * Distribution:
     * - Accounts: 4821, 9075, 3310 (roughly 40K, 35K, 25K each)
     * - Months: July-December 2026 (6 months)
     * - Categories: SPEND (60%), INCOME (15%), MICRO (20%), TRANSFER (5%)
     * - Directions: DEBIT/CREDIT based on category
     * - Message IDs: 1-2 per transaction
     */
    private List<NormalizedTxn> generateTransactions(int count, Random rand) {
        List<NormalizedTxn> transactions = new ArrayList<>();
        String[] accounts = {"4821", "9075", "3310"};
        String[] merchants = {
            "AMAZON", "SWIGGY", "BLINKIT", "IRCTC", "UBER", "NETFLIX", "SALARY",
            "BANK TRANSFER", "UPI/FRIEND", "MILK BOOTH", "GROCERY", "RESTAURANT"
        };

        for (int i = 0; i < count; i++) {
            String account = accounts[i % accounts.length];
            int monthOffset = (i / (count / 6)) % 6; // Spread across 6 months
            int day = 1 + (i / accounts.length) % 28; // 1-28
            int hour = 6 + (i % 18); // 6:00-23:59
            int minute = (i * 7) % 60;

            OffsetDateTime occurredAt = OffsetDateTime.parse("2026-07-01T06:00:00+05:30")
                    .plusMonths(monthOffset)
                    .plusDays(day - 1)
                    .withHour(hour)
                    .withMinute(minute);

            Category category;
            if (i % 100 < 60) {
                category = Category.SPEND;
            } else if (i % 100 < 75) {
                category = Category.INCOME;
            } else if (i % 100 < 95) {
                category = Category.MICRO;
            } else {
                category = Category.TRANSFER;
            }

            Direction direction = (category == Category.INCOME || category == Category.TRANSFER && i % 2 == 0)
                    ? Direction.CREDIT
                    : Direction.DEBIT;

            BigDecimal amount;
            if (category == Category.MICRO) {
                amount = new BigDecimal(50 + rand.nextInt(50)); // 50-99
            } else if (category == Category.INCOME) {
                amount = new BigDecimal(1000 + rand.nextInt(50000)); // 1K-50K
            } else if (category == Category.TRANSFER) {
                amount = new BigDecimal(5000 + rand.nextInt(15000)); // 5K-20K
            } else {
                amount = new BigDecimal(100 + rand.nextInt(5000)); // 100-5099
            }
            amount = amount.setScale(2);

            String merchant = merchants[i % merchants.length];
            List<String> messageIds = new ArrayList<>();
            messageIds.add("m-" + String.format("%06d", i) + "-" + randomHex(6, rand));
            if (rand.nextDouble() < 0.1) { // 10% have 2 message IDs
                messageIds.add("m-" + String.format("%06d", i + 100_000) + "-" + randomHex(6, rand));
            }

            transactions.add(new NormalizedTxn(
                    account,
                    occurredAt,
                    direction,
                    amount,
                    category,
                    merchant,
                    messageIds
            ));
        }

        return transactions;
    }

    /**
     * Find the month with the most transactions for a given account.
     */
    private YearMonth findBusiestMonth(List<NormalizedTxn> accountTxns) {
        return accountTxns.stream()
                .map(t -> YearMonth.from(t.occurredAt()))
                .distinct()
                .sorted((a, b) -> {
                    long countA = accountTxns.stream()
                            .filter(t -> YearMonth.from(t.occurredAt()).equals(a))
                            .count();
                    long countB = accountTxns.stream()
                            .filter(t -> YearMonth.from(t.occurredAt()).equals(b))
                            .count();
                    return Long.compare(countB, countA);
                })
                .findFirst()
                .orElse(YearMonth.of(2026, 7));
    }

    private String randomHex(int length, Random rand) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(String.format("%x", rand.nextInt(16)));
        }
        return sb.toString();
    }
}
