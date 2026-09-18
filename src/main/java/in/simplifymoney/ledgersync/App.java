package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.report.BalanceObservation;
import in.simplifymoney.ledgersync.report.Reports;
import in.simplifymoney.ledgersync.store.SqlLedgerStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Command line entry point.
 *
 *   migrate                  apply db/migration/*.sql
 *   ingest  <corpus.jsonl>   read a corpus into the ledger
 *   report  <out-dir>        write ledger.json, summary.json, reconciliation.json
 */
public final class App {

    private static final Path DB = Path.of("data", "ledger");
    private static final Path OBSERVATIONS = Path.of("data", "balance-observations.json");
    private static final Path MIGRATIONS = Path.of("db", "migration");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: migrate | ingest <corpus.jsonl> | report <out-dir>");
            System.exit(2);
        }
        Files.createDirectories(DB.getParent());

        switch (args[0]) {
            case "migrate" -> {
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    store.migrate(MIGRATIONS);
                    System.out.println("ledger rows: " + store.count());
                }
            }
            case "ingest" -> {
                if (args.length < 2) throw new IllegalArgumentException("ingest needs a corpus");
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    store.migrate(MIGRATIONS);
                    var stats = new IngestService(new Parsers(), store)
                            .ingestFile(Path.of(args[1]));
                    writeObservations(stats.balanceObservations());
                    System.out.println(stats);
                    System.out.println("ledger rows: " + store.count());
                }
            }
            case "report" -> {
                if (args.length < 2) throw new IllegalArgumentException("report needs a directory");
                Path out = Path.of(args[1]);
                Files.createDirectories(out);
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    var ledger = store.all();
                    var observations = readObservations();
                    Files.writeString(out.resolve("ledger.json"),
                            Json.writePretty(Reports.ledgerDocument(ledger)));
                    Files.writeString(out.resolve("summary.json"),
                            Json.writePretty(Reports.summary(ledger)));
                    Files.writeString(out.resolve("reconciliation.json"),
                            Json.writePretty(Reports.reconciliation(ledger, observations)));
                    System.out.println("wrote 3 files to " + out);
                }
            }
            default -> {
                System.err.println("unknown command: " + args[0]);
                System.exit(2);
            }
        }
    }

    private static void writeObservations(List<BalanceObservation> observations) throws Exception {
        List<Object> rows = new ArrayList<>();
        for (BalanceObservation observation : observations) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("account_last4", observation.accountLast4());
            row.put("occurred_at", observation.occurredAt().toString());
            row.put("direction", observation.direction().name());
            row.put("amount", observation.amount().toPlainString());
            row.put("stated_balance", observation.statedBalance().toPlainString());
            row.put("source_message_id", observation.sourceMessageId());
            rows.add(row);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("observations", rows);
        Files.writeString(OBSERVATIONS, Json.writePretty(doc));
    }

    @SuppressWarnings("unchecked")
    private static List<BalanceObservation> readObservations() throws Exception {
        if (!Files.exists(OBSERVATIONS)) return List.of();
        Map<String, Object> doc = Json.parseObject(Files.readString(OBSERVATIONS));
        List<Object> rows = (List<Object>) doc.getOrDefault("observations", List.of());
        List<BalanceObservation> out = new ArrayList<>();
        for (Object rowObj : rows) {
            Map<String, Object> row = (Map<String, Object>) rowObj;
            out.add(new BalanceObservation(
                    (String) row.get("account_last4"),
                    java.time.OffsetDateTime.parse((String) row.get("occurred_at")),
                    in.simplifymoney.ledgersync.model.Direction.valueOf((String) row.get("direction")),
                    new java.math.BigDecimal((String) row.get("amount")),
                    new java.math.BigDecimal((String) row.get("stated_balance")),
                    (String) row.get("source_message_id")));
        }
        return out;
    }
}
