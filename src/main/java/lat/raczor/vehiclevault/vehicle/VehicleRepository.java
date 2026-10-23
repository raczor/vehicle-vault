package lat.raczor.vehiclevault.vehicle;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** No tenant filter in the SQL on purpose: RLS does it. Callers must run inside a TenantSession.bind() transaction. */
@Repository
public class VehicleRepository {

    private static final String COLS = """
            id, tenant_id, make, model, year, created_at, pii_enc, vin, plate, owner_name, encrypted_at""";

    private final JdbcClient jdbc;

    VehicleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(VehicleRow v, String vinBidx) {
        jdbc.sql("""
                insert into vehicles (id, tenant_id, make, model, year, pii_enc, vin_bidx, encrypted_at)
                values (:id, :tenant, :make, :model, :year, :pii, :bidx, now())""")
                .param("id", v.id()).param("tenant", v.tenantId()).param("make", v.make())
                .param("model", v.model()).param("year", v.year()).param("pii", v.piiEnc())
                .param("bidx", vinBidx).update();
    }

    Optional<VehicleRow> find(UUID id) {
        return jdbc.sql("select " + COLS + " from vehicles where id = :id").param("id", id)
                .query(VehicleRepository::map).optional();
    }

    List<VehicleRow> list(int limit) {
        return jdbc.sql("select " + COLS + " from vehicles order by created_at desc limit :n").param("n", limit)
                .query(VehicleRepository::map).list();
    }

    /** Matches migrated rows via the blind index and not-yet-migrated rows via legacy plaintext. */
    Optional<VehicleRow> findByVin(String blindIndex, String normalizedVin) {
        return jdbc.sql("select " + COLS + " from vehicles where vin_bidx = :b or (encrypted_at is null and upper(vin) = :v) limit 1")
                .param("b", blindIndex).param("v", normalizedVin).query(VehicleRepository::map).optional();
    }

    private static VehicleRow map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new VehicleRow(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("make"), rs.getString("model"), rs.getInt("year"),
                rs.getObject("created_at", java.time.OffsetDateTime.class), rs.getString("pii_enc"),
                rs.getString("vin"), rs.getString("plate"), rs.getString("owner_name"),
                rs.getObject("encrypted_at", java.time.OffsetDateTime.class));
    }
}
