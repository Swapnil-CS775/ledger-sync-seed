package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class IngestServiceTest {

    @Test
    void canonicalizesTheCorpusMergesEvidenceAndIsIdempotent() throws Exception {
        InMemoryLedgerStore store = new InMemoryLedgerStore();
        IngestService ingest = new IngestService(new Parsers(), store);

        IngestService.Stats first = ingest.ingestFile(Path.of("fixtures/corpus-a.jsonl"));

        assertEquals(479, first.messagesRecognized());
        assertEquals(257, first.transactionsWritten());
        assertEquals(257, store.count());
        List<String> evidence = store.all().stream()
                .flatMap(transaction -> transaction.sourceMessageIds().stream())
                .toList();
        assertEquals(479, evidence.size());
        assertEquals(479, evidence.stream().distinct().count());

        NormalizedTxn salary = find(store.all(), "4821", "2026-07-01T09:02:00+05:30",
                Direction.CREDIT, "45000.00");
        assertEquals(List.of("m-00001-31eb24", "m-00002-69e4cd"), salary.sourceMessageIds());
        assertEquals(Category.INCOME, salary.category());
        assertEquals(97, store.all().stream().filter(t -> t.category() == Category.MICRO).count());
        assertEquals(10, store.all().stream().filter(t -> t.category() == Category.TRANSFER).count());

        IngestService.Stats second = ingest.ingestFile(Path.of("fixtures/corpus-a.jsonl"));
        assertEquals(479, second.messagesRecognized());
        assertEquals(257, second.transactionsWritten());
        assertEquals(257, store.count());
        List<String> reingestedEvidence = store.all().stream()
                .flatMap(transaction -> transaction.sourceMessageIds().stream())
                .toList();
        assertEquals(479, reingestedEvidence.size());
        assertEquals(479, reingestedEvidence.stream().distinct().count());
    }

    private static NormalizedTxn find(List<NormalizedTxn> transactions, String account, String occurredAt,
                                      Direction direction, String amount) {
        return transactions.stream().filter(transaction -> transaction.accountLast4().equals(account)
                        && transaction.occurredAt().equals(OffsetDateTime.parse(occurredAt))
                        && transaction.direction() == direction
                        && transaction.amount().equals(new BigDecimal(amount)))
                .findFirst().orElseThrow();
    }
}
