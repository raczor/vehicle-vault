package lat.raczor.vehiclevault.config;

import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.KmsClientBuilder;
import software.amazon.awssdk.services.kms.model.AlreadyExistsException;
import software.amazon.awssdk.services.kms.model.AliasListEntry;
import software.amazon.awssdk.services.kms.model.CreateAliasRequest;
import software.amazon.awssdk.services.kms.model.CreateKeyRequest;
import software.amazon.awssdk.services.kms.model.ListAliasesRequest;

@Configuration
class KmsConfig {

    /** Credentials come from the default AWS chain (env vars locally, IAM role in AWS). */
    @Bean
    KmsClient kmsClient(VaultProperties props) {
        KmsClientBuilder b = KmsClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.of(props.kms().region()));
        if (props.kms().endpoint() != null && !props.kms().endpoint().isBlank()) {
            b.endpointOverride(URI.create(props.kms().endpoint()));
        }
        KmsClient client = b.build();
        if (props.kms().bootstrap()) {
            ensureAlias(client, props.kms().keyAlias());
        }
        return client;
    }

    private static void ensureAlias(KmsClient client, String alias) {
        boolean exists = client.listAliasesPaginator(ListAliasesRequest.builder().build()).aliases().stream()
                .map(AliasListEntry::aliasName).anyMatch(alias::equals);
        if (!exists) {
            String keyId = client.createKey(CreateKeyRequest.builder().description("vehicle-vault PII").build())
                    .keyMetadata().keyId();
            try {
                client.createAlias(CreateAliasRequest.builder().aliasName(alias).targetKeyId(keyId).build());
            } catch (AlreadyExistsException ignored) {
                // another instance won the race; its key is the one in use
            }
        }
    }
}
