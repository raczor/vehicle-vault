package lat.raczor.vehiclevault.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.ResultSet;
import java.util.UUID;
import javax.sql.DataSource;
import lat.raczor.vehiclevault.AbstractIntegrationTest;
import lat.raczor.vehiclevault.config.VaultProperties;
import lat.raczor.vehiclevault.crypto.BlindIndex;
import lat.raczor.vehiclevault.crypto.FieldCipher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class BackfillIT extends AbstractIntegrationTest {

    @Autowired FieldCipher cipher;
    @Autowired BlindIndex blindIndex;

    /** Same wiring as the backfill profile: connects as backfill_user (BYPASSRLS), small batches, no pause. */
    private BackfillService service() {
        DataSource ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "backfill_user", "backfill_user_dev");
        var props = new VaultProperties(null, null, null, new VaultProperties.Backfill(7, 0));
        return new BackfillService(JdbcClient.create(ds), new TransactionTemplate(new DataSourceTransactionManager(ds)),
                cipher, blindIndex, props);
    }

    private void seedLegacy(UUID id, UUID tenant, String vin, String plate, String owner) throws Exception {
        try (var c = owner(); var ps = c.prepareStatement(
                "insert into vehicles (id, tenant_id, make, model, year, vin, plate, owner_name) values (?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id); ps.setObject(2, tenant); ps.setString(3, "Toyota"); ps.setString(4, "Hilux");
            ps.setInt(5, 2018); ps.setString(6, vin); ps.setString(7, plate); ps.setString(8, owner);
            ps.executeUpdate();
        }
    }

    private long count(String where) throws Exception {
        try (var c = owner(); var st = c.createStatement(); ResultSet rs = st.executeQuery("select count(*) from vehicles where " + where)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void encryptsLegacyRowsKeepsDataReadableAndIsIdempotent() throws Exception {
        UUID tenant = UUID.randomUUID(), legacy = UUID.randomUUID();
        seedLegacy(legacy, tenant, "legacyvin0000001", "P123ABC", "Legacy Owner");
        for (int i = 0; i < 20; i++) seedLegacy(UUID.randomUUID(), tenant, "FILLVIN" + String.format("%09d", i), null, null);
        assertThat(count("encrypted_at is null and tenant_id = '" + tenant + "'")).isEqualTo(21);

        BackfillService svc = service();
        assertThat(svc.run()).isGreaterThanOrEqualTo(21);

        assertThat(count("encrypted_at is null and tenant_id = '" + tenant + "'")).isZero();
        assertThat(count("tenant_id = '" + tenant + "' and (vin is not null or plate is not null or owner_name is not null)")).isZero();

        try (var c = owner(); var st = c.createStatement();
             ResultSet rs = st.executeQuery("select dek_wrapped, plate_enc, owner_name_enc, vin_bidx from vehicles where id = '" + legacy + "'")) {
            rs.next();
            var key = cipher.openRowKey(rs.getString(1), tenant, legacy);
            assertThat(key.decrypt(rs.getString(2))).isEqualTo("P123ABC");
            assertThat(key.decrypt(rs.getString(3))).isEqualTo("Legacy Owner");
            assertThat(rs.getString(4)).isEqualTo(blindIndex.vin(tenant, "LEGACYVIN0000001"));
        }

        assertThat(svc.run()).as("second run has nothing left to do").isZero();
    }

    @Test
    void aRowThatCannotBeMigratedIsSkippedAndStaysPending() throws Exception {
        UUID tenant = UUID.randomUUID(), bad = UUID.randomUUID(), good = UUID.randomUUID();
        try (var c = owner(); var st = c.createStatement()) {
            st.execute("insert into vehicles (id, tenant_id, make, model, year, vin) values ('" + bad + "','" + tenant + "','x','y',2000,null)");
        }
        seedLegacy(good, tenant, "GOODVIN0000000001", "G1", "Good");

        service().run();

        assertThat(count("id = '" + bad + "' and encrypted_at is null")).as("poison row stays pending").isEqualTo(1);
        assertThat(count("id = '" + good + "' and encrypted_at is not null")).as("others still migrate").isEqualTo(1);
    }
}
