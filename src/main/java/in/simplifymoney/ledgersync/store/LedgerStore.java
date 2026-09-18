package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.List;

/**
 * Where transactions live.
 *
 * Ingestion writes a complete canonical snapshot, so implementations replace
 * their current contents atomically enough for their storage engine.
 */
public interface LedgerStore {

    void save(NormalizedTxn txn);

    void replaceAll(List<NormalizedTxn> transactions);

    List<NormalizedTxn> all();

    long count();
}
