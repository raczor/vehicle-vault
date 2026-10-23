package lat.raczor.vehiclevault.audit;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Who touched sensitive data. One structured line per event on a dedicated logger so it can be routed to its own
 * sink. Never logs the values themselves. Shipping, retention and alerting are infrastructure (see the docs).
 */
@Component
public class AuditLog {

    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    public void record(String action, Authentication caller, UUID tenantId, UUID vehicleId) {
        audit.info("action={} subject={} tenant={} vehicle={}", action,
                caller == null ? "unknown" : caller.getName(), tenantId, vehicleId);
    }
}
