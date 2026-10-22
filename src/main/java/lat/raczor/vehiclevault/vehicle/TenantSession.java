package lat.raczor.vehiclevault.vehicle;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Binds the caller's tenant to the current DB transaction so Postgres RLS can filter by it. */
@Component
public class TenantSession {

    private final JdbcClient jdbc;

    public TenantSession(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID currentTenant() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt && jwt.getClaimAsString("tenant_id") != null) {
            try {
                return UUID.fromString(jwt.getClaimAsString("tenant_id"));
            } catch (IllegalArgumentException e) {
                throw new AccessDeniedException("tenant_id is not a UUID");
            }
        }
        throw new AccessDeniedException("token has no tenant_id");
    }

    /** Must run inside a transaction: set_config(..., is_local=true) only lives until commit/rollback. */
    public UUID bind() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("tenant must be bound inside a transaction");
        }
        UUID tenant = currentTenant();
        jdbc.sql("select set_config('app.tenant_id', :t, true)").param("t", tenant.toString()).query().singleValue();
        return tenant;
    }
}
