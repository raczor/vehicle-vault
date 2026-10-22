package lat.raczor.vehiclevault.backfill;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lat.raczor.vehiclevault.config.VaultProperties;
import lat.raczor.vehiclevault.crypto.BlindIndex;
import lat.raczor.vehiclevault.crypto.FieldCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Encrypts legacy plaintext rows in small batches while the app keeps serving traffic.
 * Safe to run repeatedly or concurrently: it only touches rows where encrypted_at IS NULL and every UPDATE
 * re-checks encrypted_at IS NULL, so a row is never encrypted twice (concurrent runners may duplicate KMS calls).
 * Plaintext is nulled in the same statement that writes the ciphertext, so there is no half-migrated state.
 * Runs as backfill_user (BYPASSRLS) because it walks all tenants.
 */
@Service
public class BackfillService {

    private static final Logger log = LoggerFactory.getLogger(BackfillService.class);

    private record Legacy(UUID id, UUID tenantId, String vin, String plate, String ownerName) {}

    private record Encrypted(UUID id, String dek, String vin, String plate, String owner, String bidx) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final FieldCipher cipher;
    private final BlindIndex blindIndex;
    private final VaultProperties.Backfill cfg;

    public BackfillService(JdbcClient jdbc, TransactionTemplate tx, FieldCipher cipher, BlindIndex blindIndex, VaultProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.cipher = cipher;
        this.blindIndex = blindIndex;
        this.cfg = props.backfill();
    }

    /**
     * Returns total rows migrated. KMS calls happen with no transaction or row lock held; only the final UPDATE of a
     * batch runs in a short transaction. Keyset paging (id > last) means a row that cannot be migrated is logged and
     * skipped instead of blocking the rest; it stays pending, so the contract step refuses to run until it is fixed.
     */
    public long run() throws InterruptedException {
        long total = 0;
        UUID last = new UUID(0, 0);
        while (true) {
            List<Legacy> rows = nextBatch(last);
            if (rows.isEmpty()) return total;
            last = rows.get(rows.size() - 1).id();
            List<Encrypted> ready = new ArrayList<>();
            for (Legacy r : rows) {
                try {
                    ready.add(encrypt(r));
                } catch (RuntimeException e) {
                    log.warn("backfill skipped row {}: {}", r.id(), e.toString());
                }
            }
            Integer done = tx.execute(s -> ready.stream().mapToInt(this::apply).sum());
            total += done == null ? 0 : done;
            log.info("backfill batch rows={} migrated={} total={} remaining={}", rows.size(), done, total, remaining());
            Thread.sleep(cfg.pauseMs());
        }
    }

    public long remaining() {
        return jdbc.sql("select count(*) from vehicles where encrypted_at is null").query(Long.class).single();
    }

    private List<Legacy> nextBatch(UUID after) {
        return jdbc.sql("""
                select id, tenant_id, vin, plate, owner_name from vehicles
                where encrypted_at is null and id > :after order by id limit :n""")
                .param("after", after).param("n", cfg.batchSize())
                .query((rs, i) -> new Legacy(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getString("vin"), rs.getString("plate"), rs.getString("owner_name")))
                .list();
    }

    private Encrypted encrypt(Legacy r) {
        if (r.vin() == null) throw new IllegalStateException("vin is null");
        var key = cipher.newRowKey(r.tenantId(), r.id());
        String vin = BlindIndex.normalize(r.vin());
        return new Encrypted(r.id(), key.wrapped(), key.encrypt(vin), key.encrypt(r.plate()),
                key.encrypt(r.ownerName()), blindIndex.vin(r.tenantId(), vin));
    }

    /** The encrypted_at IS NULL guard makes concurrent runners safe: the loser updates 0 rows. */
    private int apply(Encrypted e) {
        return jdbc.sql("""
                update vehicles set dek_wrapped = :dek, vin_enc = :vin, plate_enc = :plate, owner_name_enc = :owner,
                       vin_bidx = :bidx, encrypted_at = now(), vin = null, plate = null, owner_name = null
                where id = :id and encrypted_at is null""")
                .param("dek", e.dek()).param("vin", e.vin()).param("plate", e.plate())
                .param("owner", e.owner()).param("bidx", e.bidx()).param("id", e.id()).update();
    }
}
