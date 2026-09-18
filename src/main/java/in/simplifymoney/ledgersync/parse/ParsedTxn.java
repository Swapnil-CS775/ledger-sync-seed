package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * What a single message says, before anything has been decided about it.
 *
 * statedBalance is the account balance the bank quoted in the message, when it
 * quoted one. It may be null.
 *
 * bankReference is an internal evidence field. Different delivery channels can
 * assign different references to the same transfer, so it is deliberately not
 * exposed through the frozen NormalizedTxn contract.
 */
public record ParsedTxn(
        String accountLast4,
        OffsetDateTime occurredAt,
        Direction direction,
        BigDecimal amount,
        String merchant,
        BigDecimal statedBalance,
        String sourceMessageId,
        String bankReference) {

    /** Retains the parser constructor used before bank-reference evidence was added. */
    public ParsedTxn(String accountLast4, OffsetDateTime occurredAt, Direction direction,
                     BigDecimal amount, String merchant, BigDecimal statedBalance,
                     String sourceMessageId) {
        this(accountLast4, occurredAt, direction, amount, merchant, statedBalance,
                sourceMessageId, null);
    }
}
