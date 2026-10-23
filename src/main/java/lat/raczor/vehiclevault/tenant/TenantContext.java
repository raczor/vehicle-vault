package lat.raczor.vehiclevault.tenant;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Where the caller's tenant comes from: the tenant_id claim of the validated JWT. */
@Component
public class TenantContext {

    /** For authenticated request code: a missing or malformed claim is a 403. */
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

    /** For infrastructure that must also work without a caller (jobs, startup): empty means "no tenant bound". */
    public Optional<UUID> currentTenantIfAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt)) return Optional.empty();
        return Optional.of(currentTenant());
    }
}
