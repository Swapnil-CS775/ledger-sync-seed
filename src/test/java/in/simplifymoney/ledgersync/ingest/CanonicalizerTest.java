package in.simplifymoney.ledgersync.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class CanonicalizerTest {

    private final Canonicalizer canonicalizer = new Canonicalizer();

    @Test
    void mergesDuplicateEvidenceAndRetainsItsMetadata() {
        ParsedTxn sms = txn("m-sms", "2026-07-23T18:41:00+05:30", "52841.30", null);
        ParsedTxn email = txn("m-email", "2026-07-23T18:41:00+05:30", null, "email-reference");

        List<CanonicalTxn> canonical = canonicalizer.canonicalize(List.of(email, sms, sms));

        assertEquals(1, canonical.size());
        assertEquals(List.of("m-email", "m-sms"), canonical.get(0).evidence().stream()
                .map(ParsedTxn::sourceMessageId).toList());
        assertEquals("email-reference", canonical.get(0).evidence().get(0).bankReference());
        assertEquals(null, canonical.get(0).evidence().get(1).bankReference());
        assertEquals(null, canonical.get(0).evidence().get(0).statedBalance());
        assertEquals(new BigDecimal("52841.30"), canonical.get(0).evidence().get(1).statedBalance());
    }

    @Test
    void doesNotUseMerchantAloneOrMergeDifferentTransactionFacts() {
        ParsedTxn first = txn("m-1", "2026-07-23T18:41:00+05:30", null, null);
        ParsedTxn differentAmount = new ParsedTxn("9075", first.occurredAt(), Direction.DEBIT,
                new BigDecimal("10.00"), "UPI/BARBER", null, "m-2", null);
        ParsedTxn differentDirection = new ParsedTxn("9075", first.occurredAt(), Direction.CREDIT,
                first.amount(), "UPI/BARBER", null, "m-3", null);

        assertEquals(3, canonicalizer.canonicalize(List.of(first, differentAmount, differentDirection))
                .size());
    }

    @Test
    void keepsConflictingStatedOffsetsAsSeparateEvidenceForReconciliation() {
        ParsedTxn sms = txn("m-sms", "2026-07-19T00:20:00+05:30", "70891.55", null);
        ParsedTxn email = txn("m-email", "2026-07-18T18:50:00Z", null, "4190129089");

        assertEquals(2, canonicalizer.canonicalize(List.of(sms, email)).size());
    }

    private static ParsedTxn txn(String messageId, String occurredAt, String balance, String reference) {
        return new ParsedTxn("9075", OffsetDateTime.parse(occurredAt), Direction.DEBIT,
                new BigDecimal("5.00"), "UPI/BARBER",
                balance == null ? null : new BigDecimal(balance), messageId, reference);
    }
}
