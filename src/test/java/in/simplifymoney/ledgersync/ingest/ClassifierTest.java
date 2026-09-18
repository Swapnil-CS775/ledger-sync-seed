package in.simplifymoney.ledgersync.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClassifierTest {

    @Test
    void ownAccountDebitAndCreditBecomeTransfer() {
        CanonicalTxn debit = canonical("4821", "2026-07-05T11:00:00+05:30", Direction.DEBIT,
                "8000.00", "IMPS/P2A/OWN", "m-debit");
        CanonicalTxn credit = canonical("9075", "2026-07-05T11:02:00+05:30", Direction.CREDIT,
                "8000.00", "IMPS/P2A/OWN", "m-credit");

        List<NormalizedTxn> classified = Classifier.classify(List.of(debit, credit));

        assertEquals(Category.TRANSFER, classified.get(0).category());
        assertEquals(Category.TRANSFER, classified.get(1).category());
    }

    @Test
    void upiDebitAtOrUnder100IsMicro() {
        CanonicalTxn small = canonical("4821", "2026-07-06T20:36:00+05:30", Direction.DEBIT,
                "100.00", "UPI/WATER CAN", "m-micro");

        assertEquals(Category.MICRO, Classifier.classify(List.of(small)).get(0).category());
    }

    @Test
    void upiDebitOver100IsSpend() {
        CanonicalTxn large = canonical("4821", "2026-07-04T13:56:00+05:30", Direction.DEBIT,
                "100.01", "UPI/GROCER", "m-spend-upi");

        assertEquals(Category.SPEND, Classifier.classify(List.of(large)).get(0).category());
    }

    @Test
    void normalDebitIsSpend() {
        CanonicalTxn debit = canonical("4821", "2026-07-01T19:05:00+05:30", Direction.DEBIT,
                "1249.99", "BLINKIT", "m-spend");

        assertEquals(Category.SPEND, Classifier.classify(List.of(debit)).get(0).category());
    }

    @Test
    void normalCreditIsIncome() {
        CanonicalTxn credit = canonical("4821", "2026-07-01T09:02:00+05:30", Direction.CREDIT,
                "45000.00", "SALARY CREDIT", "m-income");

        assertEquals(Category.INCOME, Classifier.classify(List.of(credit)).get(0).category());
    }

    @Test
    void transferTakesPrecedenceOverMicro() {
        CanonicalTxn debit = canonical("4821", "2026-07-05T11:00:00+05:30", Direction.DEBIT,
                "50.00", "UPI/OWN MOVE", "m-transfer-debit");
        CanonicalTxn credit = canonical("9075", "2026-07-05T11:01:00+05:30", Direction.CREDIT,
                "50.00", "UPI/OWN MOVE", "m-transfer-credit");

        List<NormalizedTxn> classified = Classifier.classify(List.of(debit, credit));

        assertEquals(Category.TRANSFER, classified.get(0).category());
        assertEquals(Category.TRANSFER, classified.get(1).category());
    }

    @Test
    void classificationDoesNotChangeCanonicalCount() {
        List<CanonicalTxn> canonical = List.of(
                canonical("4821", "2026-07-05T11:00:00+05:30", Direction.DEBIT,
                        "8000.00", "IMPS/P2A/OWN", "m-1"),
                canonical("9075", "2026-07-05T11:02:00+05:30", Direction.CREDIT,
                        "8000.00", "IMPS/P2A/OWN", "m-2"),
                canonical("4821", "2026-07-06T20:36:00+05:30", Direction.DEBIT,
                        "20.00", "UPI/WATER CAN", "m-3"),
                canonical("4821", "2026-07-01T09:02:00+05:30", Direction.CREDIT,
                        "45000.00", "SALARY CREDIT", "m-4"));

        assertEquals(canonical.size(), Classifier.classify(canonical).size());
    }

    @Test
    void unmatchedSameMerchantOnOneAccountIsNotTransfer() {
        CanonicalTxn onlyDebit = canonical("4821", "2026-07-21T18:40:00+05:30", Direction.DEBIT,
                "12000.00", "IMPS/P2A/OTHER PERSON", "m-other");

        assertEquals(Category.SPEND, Classifier.classify(List.of(onlyDebit)).get(0).category());
    }

    private static CanonicalTxn canonical(String account, String occurredAt, Direction direction,
                                          String amount, String merchant, String messageId) {
        ParsedTxn evidence = new ParsedTxn(account, OffsetDateTime.parse(occurredAt), direction,
                new BigDecimal(amount), merchant, null, messageId, null);
        return new CanonicalTxn(CanonicalKey.from(evidence), List.of(evidence));
    }
}
