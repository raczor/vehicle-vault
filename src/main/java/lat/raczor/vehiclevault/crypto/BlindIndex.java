package lat.raczor.vehiclevault.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lat.raczor.vehiclevault.config.VaultProperties;
import org.springframework.stereotype.Component;

/** Deterministic HMAC of the VIN, scoped per tenant, so exact-match lookups work on encrypted data. */
@Component
public class BlindIndex {

    private final SecretKeySpec key;

    public BlindIndex(VaultProperties props) {
        byte[] raw = props.blindIndexKey() == null || props.blindIndexKey().isBlank()
                ? new byte[0] : Base64.getDecoder().decode(props.blindIndexKey());
        if (raw.length < 32) {
            throw new IllegalStateException("vault.blind-index-key (BLIND_INDEX_KEY) must be set to at least 32 base64-decoded bytes");
        }
        this.key = new SecretKeySpec(raw, "HmacSHA256");
    }

    public static String normalize(String vin) {
        return vin.trim().toUpperCase(Locale.ROOT);
    }

    public String vin(UUID tenantId, String vin) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal((tenantId + ":" + normalize(vin)).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
