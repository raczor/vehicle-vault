package lat.raczor.vehiclevault.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lat.raczor.vehiclevault.config.VaultProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DataKeySpec;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;

/**
 * Envelope encryption. Each row gets its own AES-256 data key from KMS; the key is stored wrapped
 * (dek_wrapped) next to the row and used for AES-GCM on every sensitive field of that row.
 * The KMS encryption context and the GCM AAD both bind the tenant and the row id, so ciphertext
 * copied to another row or tenant fails to decrypt.
 */
@Component
public class FieldCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_CACHED_KEYS = 1000;

    private final KmsClient kms;
    private final String keyAlias;
    private final SecureRandom random = new SecureRandom();

    /** Small LRU of unwrapped data keys so listing N rows does not cost N KMS calls. */
    private final Map<String, byte[]> keyCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > MAX_CACHED_KEYS;
        }
    };

    public FieldCipher(KmsClient kms, VaultProperties props) {
        this.kms = kms;
        this.keyAlias = props.kms().keyAlias();
    }

    /** A fresh data key for one row; call {@link RowKey#encrypt} for each field. */
    public RowKey newRowKey(UUID tenantId, UUID rowId) {
        var resp = kms.generateDataKey(GenerateDataKeyRequest.builder()
                .keyId(keyAlias)
                .keySpec(DataKeySpec.AES_256)
                .encryptionContext(context(tenantId, rowId))
                .build());
        String wrapped = Base64.getEncoder().encodeToString(resp.ciphertextBlob().asByteArray());
        byte[] plain = resp.plaintext().asByteArray();
        synchronized (keyCache) {
            keyCache.put(wrapped, plain);
        }
        return new RowKey(wrapped, plain, aad(tenantId, rowId));
    }

    /** Unwraps the row's data key (cached) for decrypting its fields. */
    public RowKey openRowKey(String wrapped, UUID tenantId, UUID rowId) {
        byte[] plain;
        synchronized (keyCache) {
            plain = keyCache.get(wrapped);
        }
        if (plain == null) {
            plain = kms.decrypt(DecryptRequest.builder()
                            .ciphertextBlob(SdkBytes.fromByteArray(Base64.getDecoder().decode(wrapped)))
                            .keyId(keyAlias)
                            .encryptionContext(context(tenantId, rowId))
                            .build())
                    .plaintext().asByteArray();
            synchronized (keyCache) {
                keyCache.put(wrapped, plain);
            }
        }
        return new RowKey(wrapped, plain, aad(tenantId, rowId));
    }

    private static Map<String, String> context(UUID tenantId, UUID rowId) {
        return Map.of("tenant", tenantId.toString(), "vehicle", rowId.toString());
    }

    private static byte[] aad(UUID tenantId, UUID rowId) {
        return (tenantId + "|" + rowId).getBytes(StandardCharsets.UTF_8);
    }

    public final class RowKey {
        private final String wrapped;
        private final byte[] key;
        private final byte[] aad;

        private RowKey(String wrapped, byte[] key, byte[] aad) {
            this.wrapped = wrapped;
            this.key = key;
            this.aad = aad;
        }

        public String wrapped() {
            return wrapped;
        }

        /** Output: base64(iv || ciphertext+tag). Null in, null out. */
        public String encrypt(String plaintext) {
            if (plaintext == null) return null;
            try {
                byte[] iv = new byte[IV_BYTES];
                random.nextBytes(iv);
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
                c.updateAAD(aad);
                byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
                return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("field encryption failed", e);
            }
        }

        public String decrypt(String encoded) {
            if (encoded == null) return null;
            try {
                byte[] all = Base64.getDecoder().decode(encoded);
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
                c.updateAAD(aad);
                return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("field decryption failed", e);
            }
        }
    }
}
