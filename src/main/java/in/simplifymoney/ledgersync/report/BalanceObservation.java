package in.simplifymoney.ledgersync.report;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * A bank-stated account balance attached to a parsed message.
 *
 * Only messages that quoted a balance become observations. The observation is
 * tied to the transaction the message describes so reconciliation can check
 * whether the ledger explains the balance change.
 */
public record BalanceObservation(
        String accountLast4,
        OffsetDateTime occurredAt,
        Direction direction,
        BigDecimal amount,
        BigDecimal statedBalance,
        String sourceMessageId) {

    public BalanceObservation {
        Objects.requireNonNull(accountLast4, "accountLast4");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(statedBalance, "statedBalance");
        Objects.requireNonNull(sourceMessageId, "sourceMessageId");
    }

    public static BalanceObservation from(ParsedTxn parsed) {
        if (parsed.statedBalance() == null) {
            throw new IllegalArgumentException("parsed transaction has no stated balance");
        }
        return new BalanceObservation(parsed.accountLast4(), parsed.occurredAt(),
                parsed.direction(), parsed.amount(), parsed.statedBalance(),
                parsed.sourceMessageId());
    }
}
