package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/** Parser examples taken from the distinct transaction layouts in corpus A. */
class ParsersTest {

    private final Parsers parsers = new Parsers();

    @Test
    void readsHdfcLegacyDebitWithAnIntegerAmount() {
        ParsedTxn txn = parse(sms("AD-HDFCBK-S", "Rs 20 debited from a/c **4821 on 06-07-26 "
                + "at 20:36 to UPI/WATER CAN. Avl Bal: Rs.79,769.69."));

        assertTxn(txn, "4821", "2026-07-06T20:36:00+05:30", Direction.DEBIT,
                "20.00", "UPI/WATER CAN", "79769.69");
    }

    @Test
    void readsHdfcLegacyCredit() {
        ParsedTxn txn = parse(sms("AD-HDFCBK-S", "Rs.45,000.00 credited to a/c **4821 "
                + "on 01-07-26 at 09:02 by SALARY CREDIT. Avl Bal: Rs.93,211.40"));

        assertTxn(txn, "4821", "2026-07-01T09:02:00+05:30", Direction.CREDIT,
                "45000.00", "SALARY CREDIT", "93211.40");
    }

    @Test
    void readsHdfcMultilineSentAndReceivedLayouts() {
        ParsedTxn sent = parse(sms("AD-HDFCBK-S", "Sent INR675.67\nTo: IRCTC\n"
                + "On: 23 Jul 26 22:16\nA/c: XX4821\nAvailable Balance: INR 45679.37\n-HDFC Bank"));
        ParsedTxn received = parse(sms("AD-HDFCBK-S", "Received INR1,250.33\n"
                + "From: UPI/P2P/REFUND\nOn: 25 Jul 26 12:44\nA/c: XX4821\n"
                + "Available Balance: INR 41841.53\n-HDFC Bank"));

        assertTxn(sent, "4821", "2026-07-23T22:16:00+05:30", Direction.DEBIT,
                "675.67", "IRCTC", "45679.37");
        assertTxn(received, "4821", "2026-07-25T12:44:00+05:30", Direction.CREDIT,
                "1250.33", "UPI/P2P/REFUND", "41841.53");
    }

    @Test
    void readsHdfcCardAlerts() {
        ParsedTxn txn = parse(sms("AD-HDFCBK-S", "Rs 1,249.99 spent on HDFC Bank Card x3310 "
                + "at BLINKIT on 03-07-26 11:51. Avl Limit: Rs.196,250.03."));

        assertTxn(txn, "3310", "2026-07-03T11:51:00+05:30", Direction.DEBIT,
                "1249.99", "BLINKIT", null);
    }

    @Test
    void readsIciciLegacyDebitAndCreditLayouts() {
        ParsedTxn debit = parse(sms("VM-ICICIB-T", "Dear Customer, Acct XX9075 is debited with "
                + "INR 90 on 08/07/2026 08:27. Info: UPI/MILK BOOTH. Avl Bal Rs.54,538.09 "
                + "-ICICI Bank"));
        ParsedTxn credit = parse(sms("VM-ICICIB-T", "Dear Customer, Acct XX9075 is credited with "
                + "INR 18,000 on 01/07/2026 21:14. Info: NEFT INWARD SELF. Avl Bal "
                + "Rs.49,882.25 -ICICI Bank"));

        assertTxn(debit, "9075", "2026-07-08T08:27:00+05:30", Direction.DEBIT,
                "90.00", "UPI/MILK BOOTH", "54538.09");
        assertTxn(credit, "9075", "2026-07-01T21:14:00+05:30", Direction.CREDIT,
                "18000.00", "NEFT INWARD SELF", "49882.25");
    }

    @Test
    void readsIciciV2DebitAndCreditLayouts() {
        ParsedTxn debit = parse(sms("VM-ICICIB-T", "ICICI Bank Acct XX9075 Dr INR 5 on "
                + "23-Jul-2026 18:41; UPI/BARBER ref no 154245459403. BalAvl Rs 52,841.30"));
        ParsedTxn credit = parse(sms("VM-ICICIB-T", "ICICI Bank Acct XX9075 Cr INR 1250.33 on "
                + "23-Jul-2026 16:52; INTEREST CREDIT ref no 424353460512. BalAvl Rs 52,846.30"));

        assertTxn(debit, "9075", "2026-07-23T18:41:00+05:30", Direction.DEBIT,
                "5.00", "UPI/BARBER", "52841.30");
        assertTxn(credit, "9075", "2026-07-23T16:52:00+05:30", Direction.CREDIT,
                "1250.33", "INTEREST CREDIT", "52846.30");
        assertEquals("154245459403", debit.bankReference());
        assertEquals("424353460512", credit.bankReference());
    }

    @Test
    void readsHdfcTransactionEmailsAndRegistersTheEmailParser() {
        ParsedTxn credit = parse(email("alerts@hdfcbank.net", "Date: Wed, 01 Jul 2026 09:02:00 +0530\n"
                + "Subject: Transaction alert on your account\n\nDear Customer,\n\n"
                + "Your account ending 4821 has been credited with INR 45,000.\n"
                + "Merchant / Remarks: SALARY CREDIT\nTransaction reference: 1597155421\n\n"
                + "This is a system generated email."));
        ParsedTxn debit = parse(email("alerts@hdfcbank.net", "Date: Thu, 02 Jul 2026 11:04:00 +0530\n"
                + "Subject: Transaction alert on your account\n\nDear Customer,\n\n"
                + "Your account ending 4821 has been debited with Rs.76.49.\n"
                + "Merchant / Remarks: RELIANCE SMART\nTransaction reference: 7125305049\n\n"
                + "This is a system generated email."));

        assertTxn(credit, "4821", "2026-07-01T09:02:00+05:30", Direction.CREDIT,
                "45000.00", "SALARY CREDIT", null);
        assertTxn(debit, "4821", "2026-07-02T11:04:00+05:30", Direction.DEBIT,
                "76.49", "RELIANCE SMART", null);
        assertEquals("1597155421", credit.bankReference());
        assertEquals("7125305049", debit.bankReference());
    }

    @Test
    void readsIciciTransactionEmailsAndTheUtcDateHeaderSeenInTheCorpus() {
        ParsedTxn icici = parse(email("alerts@icicibank.com", "Date: Mon, 06 Jul 2026 11:25:00 +0530\n"
                + "Subject: Transaction alert on your account\n\nDear Customer,\n\n"
                + "Your account ending 9075 has been debited with Rs.129.67.\n"
                + "Merchant / Remarks: RELIANCE SMART\nTransaction reference: 6576810104\n\n"
                + "This is a system generated email."));
        ParsedTxn utc = parse(email("alerts@hdfcbank.net", "Date: Sat, 18 Jul 2026 18:50:00 +0000\n"
                + "Subject: Transaction alert on your account\n\nDear Customer,\n\n"
                + "Your account ending 4821 has been debited with INR 412.67.\n"
                + "Merchant / Remarks: UBER INDIA\nTransaction reference: 4190129089\n\n"
                + "This is a system generated email."));

        assertTxn(icici, "9075", "2026-07-06T11:25:00+05:30", Direction.DEBIT,
                "129.67", "RELIANCE SMART", null);
        assertTxn(utc, "4821", "2026-07-18T18:50:00Z", Direction.DEBIT,
                "412.67", "UBER INDIA", null);
    }

    @Test
    void ignoresNonTransactionMessages() {
        assertFalse(parsers.parse(sms("AD-HDFCBK-S", "268880 is your OTP for txn of Rs.5160.00 "
                + "on HDFC Bank Card. Valid for 5 min.")).isPresent());
        assertFalse(parsers.parse(sms("AD-HDFCBK-S", "Avl Bal in a/c **4821 is Rs.27,244.49 "
                + "as on 30-07-26. Download HDFC Bank MobileBanking app.")).isPresent());
        assertFalse(parsers.parse(sms("AD-HDFCBK-S", "E-mandate! Rs.649.00 will be deducted from "
                + "your HDFC Bank A/c XX4821 on 22-07-26 at 06:15 for NETFLIX ENTERTAINMENT."
                + " Avl Bal: Rs.46,868.04")).isPresent());
        assertFalse(parsers.parse(sms("VM-ICICIB-T", "Get a pre-approved Personal Loan of upto "
                + "Rs.5,00,000 at 10.5% p.a. Click to know more. T&C apply. -ICICI Bank"))
                .isPresent());
        assertFalse(parsers.parse(sms("VK-ICICIB", "Dear Customer your ICICI netbanking will be "
                + "suspended today. Verify PAN immediately at icicibank-secure.co/152459 to avoid "
                + "debit of Rs.5126.00")).isPresent());
        assertFalse(parsers.parse(email("alerts@icicibank.com", "Date: Thu, 06 Aug 2026 09:00:00 +0530\n"
                + "Subject: Get a pre-approved Personal Loan\n\nClick to know more.")).isPresent());
    }

    private ParsedTxn parse(RawMessage message) {
        return parsers.parse(message).orElseThrow();
    }

    private static RawMessage sms(String sender, String body) {
        return new RawMessage("m-test", "sms", sender,
                OffsetDateTime.parse("2026-07-01T00:00:00+05:30"), "dev-test", body);
    }

    private static RawMessage email(String sender, String body) {
        return new RawMessage("m-test", "email", sender,
                OffsetDateTime.parse("2026-07-01T00:00:00+05:30"), "dev-test", body);
    }

    private static void assertTxn(ParsedTxn actual, String account, String occurredAt,
                                  Direction direction, String amount, String merchant,
                                  String statedBalance) {
        assertEquals(account, actual.accountLast4());
        assertEquals(OffsetDateTime.parse(occurredAt), actual.occurredAt());
        assertEquals(direction, actual.direction());
        assertEquals(new BigDecimal(amount), actual.amount());
        assertEquals(merchant, actual.merchant());
        assertEquals(statedBalance == null ? null : new BigDecimal(statedBalance),
                actual.statedBalance());
        assertEquals("m-test", actual.sourceMessageId());
    }
}
