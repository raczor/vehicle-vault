package lat.raczor.vehiclevault.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("vault")
public record VaultProperties(Kms kms, String blindIndexKey, String jwtSecret, Backfill backfill, Oidc oidc) {

    /** keyAlias must already exist in prod; bootstrap=true creates it (local floci only). */
    public record Kms(String endpoint, String region, String keyAlias, boolean bootstrap) {}

    public record Backfill(int batchSize, long pauseMs) {}

    /**
     * When jwkSetUri is set, tokens are validated against the identity provider's public keys (RS256 etc.) and the
     * shared jwtSecret is not used at all. issuer and audience, if set, must match the token's iss and aud claims (without an audience check, any token
     * the provider issued for another API would be accepted).
     */
    public record Oidc(String jwkSetUri, String issuer, String rolesClaim, String audience) {}
}
