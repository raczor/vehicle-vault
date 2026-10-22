package lat.raczor.vehiclevault.config;

import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * JWT resource server. Local/dev uses a shared HS256 secret; in a real deployment replace the decoder
 * with {@code NimbusJwtDecoder.withIssuerLocation(...)} pointing at the central identity provider.
 */
@Configuration
@ConditionalOnWebApplication
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain chain(HttpSecurity http, JwtAuthenticationConverter jwtConverter) throws Exception {
        return http
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/dev/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtConverter)))
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(VaultProperties props) {
        if (props.jwtSecret() == null || props.jwtSecret().getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("vault.jwt-secret (JWT_SECRET) must be set and at least 32 bytes");
        }
        var key = new SecretKeySpec(props.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    /** "roles" claim becomes ROLE_* authorities. */
    @Bean
    JwtAuthenticationConverter jwtConverter() {
        var c = new JwtAuthenticationConverter();
        c.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<String> roles = jwt.getClaimAsStringList("roles");
            return (roles == null ? List.<String>of() : roles).stream()
                    .<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
        });
        return c;
    }
}
