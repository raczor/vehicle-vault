package lat.raczor.vehiclevault.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("vault")
public record VaultProperties(Kms kms, String blindIndexKey, String jwtSecret, Backfill backfill) {

    /** keyAlias must already exist in prod; bootstrap=true creates it (local floci only). */
    public record Kms(String endpoint, String region, String keyAlias, boolean bootstrap) {}

    public record Backfill(int batchSize, long pauseMs) {}
}
