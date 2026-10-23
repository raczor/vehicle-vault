package lat.raczor.vehiclevault.crypto;

import com.amazonaws.encryptionsdk.AwsCrypto;
import com.amazonaws.encryptionsdk.CommitmentPolicy;
import com.amazonaws.encryptionsdk.CryptoAlgorithm;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import lat.raczor.vehiclevault.config.VaultProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.cryptography.materialproviders.IKeyring;
import software.amazon.cryptography.materialproviders.MaterialProviders;
import software.amazon.cryptography.materialproviders.model.CreateAwsKmsKeyringInput;
import software.amazon.cryptography.materialproviders.model.MaterialProvidersConfig;

/**
 * Envelope encryption with the AWS Encryption SDK instead of hand-rolled crypto. One versioned, self-describing
 * message per row holds all the sensitive fields: the SDK asks KMS for a data key, encrypts locally, and embeds the
 * wrapped key, the algorithm and the encryption context in the message.
 *
 * <ul>
 *   <li>Commitment policy RequireEncryptRequireDecrypt: the SDK refuses non-committing (weaker) messages.</li>
 *   <li>Unsigned committing suite: the default suite adds an extra key ("aws-crypto-public-key") to the
 *       encryption context, which would not match the IAM condition that allows only tenant and vehicle.</li>
 *   <li>Encryption context {tenant, vehicle}: authenticated, checked on decrypt, logged by CloudTrail.</li>
 *   <li>The key is resolved to its ARN once at startup because decrypt requires an ARN, not an alias.</li>
 * </ul>
 */
@Component
public class PiiCipher {

    public record Pii(String vin, String plate, String ownerName) {}

    private final AwsCrypto crypto = AwsCrypto.builder()
            .withCommitmentPolicy(CommitmentPolicy.RequireEncryptRequireDecrypt)
            .withEncryptionAlgorithm(CryptoAlgorithm.ALG_AES_256_GCM_HKDF_SHA512_COMMIT_KEY)
            .build();
    private final IKeyring keyring;
    // Unknown fields are ignored on read so a newer writer can add a field during a rolling deploy without
    // breaking older instances that still read the same rows.
    private final ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public PiiCipher(KmsClient kms, VaultProperties props) {
        String keyArn = kms.describeKey(r -> r.keyId(props.kms().keyAlias())).keyMetadata().arn();
        this.keyring = MaterialProviders.builder()
                .MaterialProvidersConfig(MaterialProvidersConfig.builder().build())
                .build()
                .CreateAwsKmsKeyring(CreateAwsKmsKeyringInput.builder().kmsKeyId(keyArn).kmsClient(kms).build());
    }

    /** Returns base64 of the encrypted message. */
    public String encrypt(UUID tenantId, UUID rowId, Pii pii) {
        try {
            byte[] plain = json.writeValueAsBytes(pii);
            byte[] message = crypto.encryptData(keyring, plain, context(tenantId, rowId)).getResult();
            return Base64.getEncoder().encodeToString(message);
        } catch (Exception e) {
            throw new IllegalStateException("PII encryption failed", e);
        }
    }

    /** Fails if the message was encrypted for a different tenant or row (the supplied context must match). */
    public Pii decrypt(UUID tenantId, UUID rowId, String encoded) {
        try {
            byte[] message = Base64.getDecoder().decode(encoded.getBytes(StandardCharsets.UTF_8));
            byte[] plain = crypto.decryptData(keyring, message, context(tenantId, rowId)).getResult();
            return json.readValue(plain, Pii.class);
        } catch (Exception e) {
            throw new IllegalStateException("PII decryption failed", e);
        }
    }

    private static Map<String, String> context(UUID tenantId, UUID rowId) {
        return Map.of("tenant", tenantId.toString(), "vehicle", rowId.toString());
    }
}
