package lat.raczor.vehiclevault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import lat.raczor.vehiclevault.config.JwtDecoderFactory;
import lat.raczor.vehiclevault.config.VaultProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/** OIDC mode against a local JWKS endpoint standing in for an identity provider. */
class JwtDecoderFactoryTest {

    static final String ISSUER = "https://idp.example.test";
    static final String DEV_SECRET = "dev-only-secret-change-me-0123456789abcdef";
    static HttpServer jwks;
    static RSAKey rsa;
    static JwtDecoder decoder;

    @BeforeAll
    static void start() throws Exception {
        rsa = new RSAKeyGenerator(2048).keyID("k1").generate();
        byte[] body = new JWKSet(rsa.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwks.createContext("/jwks", ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        jwks.start();
        var props = new VaultProperties(null, null, DEV_SECRET, null, new VaultProperties.Oidc(
                "http://127.0.0.1:" + jwks.getAddress().getPort() + "/jwks", ISSUER, "roles", "vehicle-vault"));
        decoder = JwtDecoderFactory.create(props);
    }

    @AfterAll
    static void stop() {
        jwks.stop(0);
    }

    private static String rs256(String issuer, long expiresInMs) throws Exception {
        return rs256(issuer, "vehicle-vault", expiresInMs);
    }

    private static String rs256(String issuer, String audience, long expiresInMs) throws Exception {
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(),
                new JWTClaimsSet.Builder().subject("u").issuer(issuer).claim("tenant_id", "t").audience(audience)
                        .expirationTime(new Date(System.currentTimeMillis() + expiresInMs)).build());
        jwt.sign(new RSASSASigner(rsa));
        return jwt.serialize();
    }

    @Test
    void acceptsATokenSignedByTheIdentityProvider() throws Exception {
        assertThat(decoder.decode(rs256(ISSUER, 60_000)).getSubject()).isEqualTo("u");
    }

    @Test
    void rejectsAWrongIssuerAnExpiredTokenAndTheDevSharedSecret() throws Exception {
        assertThatThrownBy(() -> decoder.decode(rs256("https://evil.example", 60_000))).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(rs256(ISSUER, -120_000))).isInstanceOf(JwtException.class);
        // a token the provider issued for a different API must not be accepted here
        assertThatThrownBy(() -> decoder.decode(rs256(ISSUER, "some-other-api", 60_000))).isInstanceOf(JwtException.class);

        var forged = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256),
                new JWTClaimsSet.Builder().subject("u").issuer(ISSUER).claim("tenant_id", "t")
                        .expirationTime(new Date(System.currentTimeMillis() + 60_000)).build());
        forged.sign(new MACSigner(DEV_SECRET.getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> decoder.decode(forged.serialize())).isInstanceOf(JwtException.class);
    }

    @Test
    void withoutOidcAndWithoutASufficientSecretStartupFails() {
        var weak = new VaultProperties(null, null, "short", null, new VaultProperties.Oidc("", "", "roles", ""));
        assertThatThrownBy(() -> JwtDecoderFactory.create(weak)).isInstanceOf(IllegalStateException.class);
    }
}
