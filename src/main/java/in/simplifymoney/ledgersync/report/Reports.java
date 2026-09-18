package in.simplifymoney.ledgersync.report;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Builds the three assignment output documents from a classified canonical ledger.
 *
 * summary() totals by category. reconciliation() compares consecutive bank-stated
 * balance observations against the net movement the ledger records between them.
 */
public final class Reports {

    private Reports() {}

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    public static Map<String, Object> summary(List<NormalizedTxn> ledger) {
        Map<String, Object> accounts = new LinkedHashMap<>();
        for (String acct : new TreeSet<>(ledger.stream()
                .map(NormalizedTxn::accountLast4).toList())) {

            BigDecimal spend = ZERO;
            BigDecimal income = ZERO;
            BigDecimal microTotal = ZERO;
            BigDecimal transferredOut = ZERO;
            BigDecimal transferredIn = ZERO;
            int microCount = 0;

            for (NormalizedTxn t : ledger) {
                if (!t.accountLast4().equals(acct)) continue;
                switch (t.category()) {
                    case SPEND -> spend = spend.add(t.amount());
                    case INCOME -> income = income.add(t.amount());
                    case MICRO -> {
                        microCount++;
                        microTotal = microTotal.add(t.amount());
                    }
                    case TRANSFER -> {
                        if (t.direction() == Direction.DEBIT) {
                            transferredOut = transferredOut.add(t.amount());
                        } else {
                            transferredIn = transferredIn.add(t.amount());
                        }
                    }
                }
            }

            Map<String, Object> a = new LinkedHashMap<>();
            a.put("spend", spend.toPlainString());
            a.put("income", income.toPlainString());
            a.put("micro_count", microCount);
            a.put("micro_total", microTotal.toPlainString());
            a.put("transferred_out", transferredOut.toPlainString());
            a.put("transferred_in", transferredIn.toPlainString());
            accounts.put(acct, a);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("accounts", accounts);
        return doc;
    }

    public static Map<String, Object> ledgerDocument(List<NormalizedTxn> ledger) {
        List<Object> rows = ledger.stream().map(t -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("account_last4", t.accountLast4());
            r.put("occurred_at", t.occurredAt().toString());
            r.put("direction", t.direction().name().toLowerCase());
            r.put("amount", t.amount().toPlainString());
            r.put("category", t.category().name());
            r.put("merchant", t.merchant());
            r.put("source_message_ids", t.sourceMessageIds());
            return (Object) r;
        }).toList();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("transactions", rows);
        return doc;
    }

    /**
     * Compares consecutive stated-balance observations with the ledger net between
     * them. Unexplained differences are reported; nothing is invented to close them.
     */
    public static Map<String, Object> reconciliation(List<NormalizedTxn> ledger,
                                                     List<BalanceObservation> observations) {
        List<Object> discrepancies = new ArrayList<>();
        Map<String, List<NormalizedTxn>> byAccount = new TreeMap<>();
        for (NormalizedTxn txn : ledger) {
            byAccount.computeIfAbsent(txn.accountLast4(), ignored -> new ArrayList<>()).add(txn);
        }

        Map<String, List<BalanceObservation>> observationsByAccount =
                dedupeAndGroup(observations == null ? List.of() : observations);

        TreeSet<String> accounts = new TreeSet<>();
        accounts.addAll(byAccount.keySet());
        accounts.addAll(observationsByAccount.keySet());

        for (String account : accounts) {
            List<BalanceObservation> accountObs =
                    observationsByAccount.getOrDefault(account, List.of());
            List<NormalizedTxn> accountLedger = byAccount.getOrDefault(account, List.of());
            for (int i = 1; i < accountObs.size(); i++) {
                BalanceObservation previous = accountObs.get(i - 1);
                BalanceObservation current = accountObs.get(i);
                BigDecimal statedChange = current.statedBalance().subtract(previous.statedBalance());
                BigDecimal ledgerNet = ledgerNetBetween(accountLedger,
                        previous.occurredAt(), current.occurredAt());
                BigDecimal unexplained = statedChange.subtract(ledgerNet);
                if (unexplained.compareTo(BigDecimal.ZERO) == 0) continue;

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("account_last4", account);
                row.put("occurred_at", current.occurredAt().toString());
                row.put("amount", unexplained.abs().toPlainString());
                row.put("note", discrepancyNote(statedChange, ledgerNet, unexplained));
                discrepancies.add(row);
            }
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("discrepancies", discrepancies);
        return doc;
    }

    /** @deprecated Prefer {@link #reconciliation(List, List)} with stated-balance evidence. */
    @Deprecated
    public static Map<String, Object> reconciliation(List<NormalizedTxn> ledger) {
        return reconciliation(ledger, List.of());
    }

    public static Map<Category, BigDecimal> byCategory(List<NormalizedTxn> ledger) {
        Map<Category, BigDecimal> out = new LinkedHashMap<>();
        for (Category c : Category.values()) out.put(c, ZERO);
        for (NormalizedTxn t : ledger) {
            out.put(t.category(), out.get(t.category()).add(t.amount()));
        }
        return out;
    }

    private static Map<String, List<BalanceObservation>> dedupeAndGroup(
            List<BalanceObservation> observations) {
        Map<String, List<BalanceObservation>> byAccount = new TreeMap<>();
        Map<String, BalanceObservation> seen = new LinkedHashMap<>();
        List<BalanceObservation> ordered = new ArrayList<>(observations);
        ordered.sort(Comparator.comparing(BalanceObservation::occurredAt)
                .thenComparing(BalanceObservation::accountLast4)
                .thenComparing(BalanceObservation::sourceMessageId));
        for (BalanceObservation observation : ordered) {
            String key = observation.accountLast4() + '|'
                    + observation.occurredAt() + '|'
                    + observation.direction() + '|'
                    + observation.amount().toPlainString() + '|'
                    + observation.statedBalance().toPlainString();
            seen.putIfAbsent(key, observation);
        }
        for (BalanceObservation observation : seen.values()) {
            byAccount.computeIfAbsent(observation.accountLast4(), ignored -> new ArrayList<>())
                    .add(observation);
        }
        for (List<BalanceObservation> list : byAccount.values()) {
            list.sort(Comparator.comparing(BalanceObservation::occurredAt)
                    .thenComparing(BalanceObservation::sourceMessageId));
        }
        return byAccount;
    }

    /**
     * Net ledger movement after {@code fromExclusive} through {@code toInclusive}.
     * Credits add; debits subtract.
     */
    static BigDecimal ledgerNetBetween(List<NormalizedTxn> accountLedger,
                                       OffsetDateTime fromExclusive,
                                       OffsetDateTime toInclusive) {
        BigDecimal net = ZERO;
        for (NormalizedTxn txn : accountLedger) {
            OffsetDateTime at = txn.occurredAt();
            if (!at.isAfter(fromExclusive)) continue;
            if (at.isAfter(toInclusive)) continue;
            net = txn.direction() == Direction.DEBIT
                    ? net.subtract(txn.amount())
                    : net.add(txn.amount());
        }
        return net;
    }

    private static String discrepancyNote(BigDecimal statedChange, BigDecimal ledgerNet,
                                          BigDecimal unexplained) {
        return "stated balance changed by " + statedChange.toPlainString()
                + " since previous observation but ledger net is "
                + ledgerNet.toPlainString()
                + " (unexplained " + unexplained.toPlainString() + ")";
    }
}
