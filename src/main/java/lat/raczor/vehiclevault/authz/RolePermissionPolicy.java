package lat.raczor.vehiclevault.authz;

import java.util.Map;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

/** Central role to permission map. Controllers never check roles directly, only permissions via {@code @pdp}. */
@Component("pdp")
public class RolePermissionPolicy implements PolicyDecisionPoint {

    public static final String VEHICLE_READ = "vehicle:read";
    public static final String VEHICLE_READ_PII = "vehicle:read-pii";
    public static final String VEHICLE_WRITE = "vehicle:write";

    private static final Map<String, Set<String>> ROLE_PERMISSIONS = Map.of(
            "ADMIN", Set.of(VEHICLE_READ, VEHICLE_READ_PII, VEHICLE_WRITE),
            "MANAGER", Set.of(VEHICLE_READ, VEHICLE_READ_PII, VEHICLE_WRITE),
            "VIEWER", Set.of(VEHICLE_READ));

    @Override
    public boolean allowed(Authentication auth, String permission) {
        if (auth == null || !auth.isAuthenticated()) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            String role = a.getAuthority().replaceFirst("^ROLE_", "");
            if (ROLE_PERMISSIONS.getOrDefault(role, Set.of()).contains(permission)) return true;
        }
        return false;
    }
}
