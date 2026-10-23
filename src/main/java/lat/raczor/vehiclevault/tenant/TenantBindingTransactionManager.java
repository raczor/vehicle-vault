package lat.raczor.vehiclevault.tenant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Binds the caller's tenant to every database transaction automatically, so Postgres RLS can filter by it and no
 * service method can forget to. set_config(..., is_local = true) lives until commit or rollback, so a pooled
 * connection never carries one request's tenant into the next.
 *
 * With no authenticated caller (the backfill job, startup) nothing is bound; for the app_user role that means
 * RLS returns no rows, and the backfill role bypasses RLS on purpose.
 */
public class TenantBindingTransactionManager extends DataSourceTransactionManager {

    private final TenantContext tenants;

    public TenantBindingTransactionManager(DataSource dataSource, TenantContext tenants) {
        super(dataSource);
        this.tenants = tenants;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        try {
            UUID tenant = tenants.currentTenantIfAuthenticated().orElse(null);
            if (tenant != null) {
                var holder = (ConnectionHolder) TransactionSynchronizationManager.getResource(obtainDataSource());
                bind(holder.getConnection(), tenant);
            }
        } catch (SQLException e) {
            doCleanupAfterCompletion(transaction);
            throw new CannotCreateTransactionException("could not bind tenant to the transaction", e);
        } catch (RuntimeException e) {
            doCleanupAfterCompletion(transaction); // release the connection we just took
            throw e;
        }
    }

    private static void bind(Connection c, UUID tenant) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
            ps.setString(1, tenant.toString());
            ps.execute();
        }
    }
}
