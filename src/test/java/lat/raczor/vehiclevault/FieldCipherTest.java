package lat.raczor.vehiclevault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.UUID;
import lat.raczor.vehiclevault.config.VaultProperties;
import lat.raczor.vehiclevault.crypto.BlindIndex;
import lat.raczor.vehiclevault.crypto.FieldCipher;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;

class FieldCipherTest {

    /** In-memory stand-in for KMS: "wraps" a key by reversing it and enforces the encryption context. */
    private static KmsClient fakeKms() {
        return new KmsClient() {
            public GenerateDataKeyResponse generateDataKey(GenerateDataKeyRequest r) {
                byte[] k = new byte[32];
                new SecureRandom().nextBytes(k);
                String ctx = r.encryptionContext().toString();
                byte[] blob = (ctx + "#" + java.util.Base64.getEncoder().encodeToString(k)).getBytes();
                return GenerateDataKeyResponse.builder().plaintext(SdkBytes.fromByteArray(k))
                        .ciphertextBlob(SdkBytes.fromByteArray(blob)).build();
            }
            public DecryptResponse decrypt(DecryptRequest r) {
                String[] p = new String(r.ciphertextBlob().asByteArray()).split("#");
                if (!p[0].equals(r.encryptionContext().toString())) throw new IllegalStateException("context mismatch");
                return DecryptResponse.builder().plaintext(SdkBytes.fromByteArray(java.util.Base64.getDecoder().decode(p[1]))).build();
            }
            public String serviceName() { return "kms"; }
            public void close() {}
        };
    }

    private static VaultProperties props() {
        return new VaultProperties(new VaultProperties.Kms(null, "us-east-1", "alias/test", false),
                "ZGV2LW9ubHktYmxpbmQtaW5kZXgta2V5LTMyLWJ5dGVz", "x", null);
    }

    @Test
    void roundTripsAndNeverStoresPlaintext() {
        var cipher = new FieldCipher(fakeKms(), props());
        UUID t = UUID.randomUUID(), row = UUID.randomUUID();
        var key = cipher.newRowKey(t, row);
        String enc = key.encrypt("1HGCM82633A004352");
        assertThat(enc).doesNotContain("1HGCM82633A004352");
        assertThat(cipher.openRowKey(key.wrapped(), t, row).decrypt(enc)).isEqualTo("1HGCM82633A004352");
        assertThat(key.encrypt(null)).isNull();
    }

    @Test
    void ciphertextMovedToAnotherRowOrTenantFails() {
        var cipher = new FieldCipher(fakeKms(), props());
        UUID t = UUID.randomUUID(), row = UUID.randomUUID();
        var key = cipher.newRowKey(t, row);
        String enc = key.encrypt("secret");
        assertThatThrownBy(() -> cipher.openRowKey(key.wrapped(), UUID.randomUUID(), row).decrypt(enc))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> cipher.openRowKey(key.wrapped(), t, UUID.randomUUID()).decrypt(enc))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void blindIndexIsDeterministicNormalizedAndTenantScoped() {
        var bi = new BlindIndex(props());
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertThat(bi.vin(a, " abc12345678 ")).isEqualTo(bi.vin(a, "ABC12345678"));
        assertThat(bi.vin(a, "ABC12345678")).isNotEqualTo(bi.vin(b, "ABC12345678"));
    }
}
