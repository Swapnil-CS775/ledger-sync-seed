package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Assigns a Category to each canonical transaction.
 *
 * Precedence: TRANSFER, then MICRO, then SPEND / INCOME.
 *
 * Own-account transfers are detected from ledger evidence: a debit on one
 * known account paired with a credit on another for the same amount, merchant
 * and near-identical bank timestamp. Classification never invents transfers
 * from direction alone.
 */
public final class Classifier {

    static final BigDecimal MICRO_MAX = new BigDecimal("100.00");
    static final Duration TRANSFER_WINDOW = Duration.ofMinutes(5);

    private Classifier() {}

    public static List<NormalizedTxn> classify(List<CanonicalTxn> canonical) {
        Set<CanonicalKey> transfers = findTransferKeys(canonical);
        List<NormalizedTxn> out = new ArrayList<>(canonical.size());
        for (CanonicalTxn transaction : canonical) {
            out.add(toNormalized(transaction, categoryFor(transaction, transfers)));
        }
        return List.copyOf(out);
    }

    static Category categoryFor(CanonicalTxn transaction, Set<CanonicalKey> transfers) {
        if (transfers.contains(transaction.key())) {
            return Category.TRANSFER;
        }
        ParsedTxn representative = transaction.evidence().get(0);
        if (representative.direction() == Direction.DEBIT
                && isUpi(representative.merchant())
                && representative.amount().compareTo(MICRO_MAX) <= 0) {
            return Category.MICRO;
        }
        return representative.direction() == Direction.DEBIT ? Category.SPEND : Category.INCOME;
    }

    static boolean isUpi(String merchant) {
        if (merchant == null || merchant.isBlank()) return false;
        return merchant.toUpperCase(Locale.ROOT).contains("UPI");
    }

    private static Set<CanonicalKey> findTransferKeys(List<CanonicalTxn> canonical) {
        List<CanonicalTxn> ordered = new ArrayList<>(canonical);
        ordered.sort(Comparator
                .comparing((CanonicalTxn c) -> c.key().occurredAt())
                .thenComparing(c -> c.key().accountLast4())
                .thenComparing(c -> c.key().direction())
                .thenComparing(c -> c.key().amount()));

        Set<CanonicalKey> transfers = new HashSet<>();
        Set<CanonicalKey> used = new HashSet<>();

        for (CanonicalTxn debit : ordered) {
            if (debit.key().direction() != Direction.DEBIT) continue;
            if (used.contains(debit.key())) continue;

            CanonicalTxn match = closestOppositeLeg(debit, ordered, used);
            if (match == null) continue;

            transfers.add(debit.key());
            transfers.add(match.key());
            used.add(debit.key());
            used.add(match.key());
        }
        return transfers;
    }

    private static CanonicalTxn closestOppositeLeg(CanonicalTxn debit, List<CanonicalTxn> ordered,
                                                   Set<CanonicalKey> used) {
        CanonicalTxn best = null;
        long bestSeconds = Long.MAX_VALUE;
        OffsetDateTime debitAt = debit.key().occurredAt();

        for (CanonicalTxn candidate : ordered) {
            if (used.contains(candidate.key())) continue;
            if (candidate.key().direction() != Direction.CREDIT) continue;
            if (candidate.key().accountLast4().equals(debit.key().accountLast4())) continue;
            if (candidate.key().amount().compareTo(debit.key().amount()) != 0) continue;
            if (!sameMerchant(debit, candidate)) continue;

            long seconds = Math.abs(Duration.between(debitAt, candidate.key().occurredAt()).getSeconds());
            if (seconds > TRANSFER_WINDOW.getSeconds()) continue;
            if (seconds < bestSeconds) {
                bestSeconds = seconds;
                best = candidate;
            }
        }
        return best;
    }

    private static boolean sameMerchant(CanonicalTxn left, CanonicalTxn right) {
        String a = normalizeMerchant(left.evidence().get(0).merchant());
        String b = normalizeMerchant(right.evidence().get(0).merchant());
        return !a.isEmpty() && a.equals(b);
    }

    private static String normalizeMerchant(String merchant) {
        return merchant == null ? "" : merchant.trim().toUpperCase(Locale.ROOT);
    }

    private static NormalizedTxn toNormalized(CanonicalTxn canonical, Category category) {
        ParsedTxn representative = canonical.evidence().get(0);
        List<String> sourceIds = canonical.evidence().stream()
                .map(ParsedTxn::sourceMessageId)
                .sorted()
                .toList();
        return new NormalizedTxn(representative.accountLast4(), representative.occurredAt(),
                representative.direction(), representative.amount(), category,
                representative.merchant(), sourceIds);
    }
}
