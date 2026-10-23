package lat.raczor.vehiclevault.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

/**
 * Optional: when DB_SECRET_ID is set, the service's database credentials are read from AWS Secrets Manager
 * at startup instead of plain env vars. Only the service's own (app_user) secret is ever read here: schema
 * migrations run as a separate job with the owner credentials. The secret JSON is the one Terraform writes
 * ({"username":..., "password":..., "host":..., "port":..., "dbname":...}); only username and password are used,
 * the JDBC URL still comes from DB_URL (inside a container network the secret's host is not the one to dial).
 * Needs secretsmanager:GetSecretValue on those secrets, which infra/modules/app_access grants.
 */
public class SecretsManagerEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        String dbSecret = env.getProperty("DB_SECRET_ID");
        if (dbSecret == null) return;

        Map<String, Object> props = new HashMap<>();
        try (SecretsManagerClient client = client(env)) {
            JsonNode s = read(client, dbSecret);
            props.put("spring.datasource.username", s.get("username").asText());
            props.put("spring.datasource.password", s.get("password").asText());
        }
        env.getPropertySources().addFirst(new MapPropertySource("secretsManager", props));
    }

    private static SecretsManagerClient client(ConfigurableEnvironment env) {
        var b = SecretsManagerClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.of(env.getProperty("AWS_REGION", "us-east-1")));
        String endpoint = env.getProperty("AWS_ENDPOINT_URL");
        if (endpoint != null && !endpoint.isBlank()) b.endpointOverride(URI.create(endpoint));
        return b.build();
    }

    private static JsonNode read(SecretsManagerClient client, String id) {
        try {
            String json = client.getSecretValue(GetSecretValueRequest.builder().secretId(id).build()).secretString();
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("could not read secret " + id, e);
        }
    }
}
