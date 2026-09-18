package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank transaction alert emails.
 *
 * HDFC and ICICI use the same structured transaction-alert body in this corpus.
 * Restricting this parser to their alert senders and subject line avoids turning
 * ordinary bank emails into transactions.
 */
public final class EmailParser implements MessageParser {

    public static final String HDFC_SENDER = "alerts@hdfcbank.net";
    public static final String ICICI_SENDER = "alerts@icicibank.com";

    private static final Pattern ALERT = Pattern.compile(
            "(?ms)^Date: (?<when>[^\\r\\n]+)\\r?\\n"
                    + "Subject: Transaction alert on your account\\r?\\n.*?"
                    + "^Your account ending (?<acct>\\d{4}) has been "
                    + "(?<dir>debited|credited) with .+?\\.\\r?\\n"
                    + "Merchant / Remarks: (?<merchant>[^\\r\\n]+)\\r?\\n"
                    + "Transaction reference: (?<reference>[^\\r\\n]+)$");

    @Override
    public boolean supports(RawMessage m) {
        return "email".equals(m.channel())
                && (HDFC_SENDER.equals(m.sender()) || ICICI_SENDER.equals(m.sender()));
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        Matcher alert = ALERT.matcher(m.body());
        if (!alert.find()) return Optional.empty();

        BigDecimal amount = Amounts.first(m.body());
        OffsetDateTime at;
        try {
            at = OffsetDateTime.parse(alert.group("when"), DateTimeFormatter.RFC_1123_DATE_TIME);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
        if (amount == null) return Optional.empty();

        Direction direction = "debited".equals(alert.group("dir"))
                ? Direction.DEBIT : Direction.CREDIT;
        return Optional.of(new ParsedTxn(alert.group("acct"), at, direction, amount,
                alert.group("merchant").trim(), Amounts.statedBalance(m.body()), m.messageId(),
                alert.group("reference").trim()));
    }
}
