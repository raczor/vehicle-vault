package lat.raczor.vehiclevault.authz;

import org.springframework.security.core.Authentication;

/** The single place authorization decisions are made. Swap the implementation for OPA/Cedar/etc. later. */
public interface PolicyDecisionPoint {
    boolean allowed(Authentication auth, String permission);
}
