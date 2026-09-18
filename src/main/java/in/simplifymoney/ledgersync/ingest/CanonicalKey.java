package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Channel-neutral identity for one bank movement.
 *
 * A bank reference is retained as evidence, but cannot be the whole key: the
 * ICICI SMS and email notifications for one movement can carry different
 * references. Account, bank-stated timestamp (including its offset), direction
 * and amount are present in every transaction format and remain stable across
 * matching representations. A conflicting offset is retained as a separate
 * event for later reconciliation rather than silently normalised away.
 */
public record CanonicalKey(
        String accountLast4,
        OffsetDateTime occurredAt,
        Direction direction,
        BigDecimal amount) implements Comparable<CanonicalKey> {

    public static CanonicalKey from(ParsedTxn message) {
        return new CanonicalKey(message.accountLast4(), message.occurredAt(),
                message.direction(), message.amount());
    }

    public static CanonicalKey from(NormalizedTxn transaction) {
        return new CanonicalKey(transaction.accountLast4(), transaction.occurredAt(),
                transaction.direction(), transaction.amount());
    }

    @Override
    public int compareTo(CanonicalKey other) {
        int result = accountLast4.compareTo(other.accountLast4);
        if (result != 0) return result;
        result = occurredAt.compareTo(other.occurredAt);
        if (result != 0) return result;
        result = direction.compareTo(other.direction);
        if (result != 0) return result;
        return amount.compareTo(other.amount);
    }
}
