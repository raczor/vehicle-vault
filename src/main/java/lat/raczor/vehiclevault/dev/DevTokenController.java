package lat.raczor.vehiclevault.dev;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lat.raczor.vehiclevault.config.VaultProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.JWKSet;

/** Local-only token minting so the API can be tried without an identity provider. Never enable in prod. */
@RestController
@Profile("dev")
class DevTokenController {

    private final JwtEncoder encoder;

    DevTokenController(VaultProperties props) {
        var jwk = new OctetSequenceKey.Builder(props.jwtSecret().getBytes(StandardCharsets.UTF_8)).build();
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
    }

    @GetMapping("/dev/token")
    String token(@RequestParam UUID tenant, @RequestParam(defaultValue = "MANAGER") List<String> roles) {
        var claims = JwtClaimsSet.builder().subject("dev-user").claim("tenant_id", tenant.toString())
                .claim("roles", roles).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
