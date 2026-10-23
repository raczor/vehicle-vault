package lat.raczor.vehiclevault.config;

import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** OIDC mode (JWKS from an identity provider) when configured, shared-secret HS256 for local development otherwise. */
public final class JwtDecoderFactory {

    private JwtDecoderFactory() {}

    public static JwtDecoder create(VaultProperties props) {
        var oidc = props.oidc();
        if (oidc != null && oidc.jwkSetUri() != null && !oidc.jwkSetUri().isBlank()) {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(oidc.jwkSetUri()).build();
            OAuth2TokenValidator<Jwt> validator = oidc.issuer() == null || oidc.issuer().isBlank()
                    ? JwtValidators.createDefault() : JwtValidators.createDefaultWithIssuer(oidc.issuer());
            if (oidc.audience() == null || oidc.audience().isBlank()) {
                decoder.setJwtValidator(validator);
            } else {
                OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
                        "aud", aud -> aud != null && aud.contains(oidc.audience()));
                decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validator, audience));
            }
            return decoder;
        }
        if (props.jwtSecret() == null || props.jwtSecret().getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "set vault.oidc.jwk-set-uri (OIDC_JWK_SET_URI), or for local dev a vault.jwt-secret (JWT_SECRET) of at least 32 bytes");
        }
        var key = new SecretKeySpec(props.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }
}
