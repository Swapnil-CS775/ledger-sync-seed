package in.simplifymoney.ledgersync.parse;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rupee amounts as banks write them.
 *
 * Handles the prefixes we see in practice - "Rs.", "Rs ", "INR " - and strips
 * the thousands separators before handing back a BigDecimal.
 */
public final class Amounts {

    private Amounts() {}

    private static final String MONEY =
            "((?:[0-9]+|[0-9]{1,3}(?:,[0-9]{2,3})+)(?:\\.[0-9]{1,2})?)";

    private static final String MONEY_BOUNDARY = "(?![0-9,]|\\.[0-9])";

    private static final Pattern AMOUNT =
            Pattern.compile("(?:Rs\\.?|INR)\\s*" + MONEY + MONEY_BOUNDARY);

    /** Account available-balance quotes. Card available-limit quotes are excluded. */
    private static final Pattern ACCOUNT_BALANCE = Pattern.compile(
            "(?:Avl\\s*Bal|Available\\s*Balance|BalAvl)\\s*:?\\s*"
                    + "(?:Rs\\.?|INR)\\s*" + MONEY + MONEY_BOUNDARY,
            Pattern.CASE_INSENSITIVE);

    /** Any trailing quoted figure that must not be mistaken for the transaction amount. */
    private static final Pattern QUOTED_FIGURE = Pattern.compile(
            "(?:Avl\\s*Bal|Available\\s*Balance|BalAvl|Avl\\s*Limit)\\s*:?\\s*"
                    + "(?:Rs\\.?|INR)\\s*" + MONEY + MONEY_BOUNDARY,
            Pattern.CASE_INSENSITIVE);

    /** The transaction amount: the first rupee figure in the message. */
    public static BigDecimal first(String body) {
        Matcher m = AMOUNT.matcher(body);
        while (m.find()) {
            if (!isQuotedFigure(body, m.start(1))) return toDecimal(m.group(1));
        }
        return null;
    }

    /** The account balance the bank quoted, if it quoted one (not a card limit). */
    public static BigDecimal statedBalance(String body) {
        Matcher m = ACCOUNT_BALANCE.matcher(body);
        if (!m.find()) return null;
        return toDecimal(m.group(1));
    }

    private static BigDecimal toDecimal(String raw) {
        return new BigDecimal(raw.replace(",", "")).setScale(2);
    }

    private static boolean isQuotedFigure(String body, int amountStart) {
        Matcher quoted = QUOTED_FIGURE.matcher(body);
        while (quoted.find()) {
            if (quoted.start(1) == amountStart) return true;
        }
        return false;
    }
}
