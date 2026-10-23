package lat.raczor.vehiclevault;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import lat.raczor.vehiclevault.config.VaultProperties;
import lat.raczor.vehiclevault.crypto.BlindIndex;
import org.junit.jupiter.api.Test;

class BlindIndexTest {

    private static VaultProperties props() {
        return new VaultProperties(null, "ZGV2LW9ubHktYmxpbmQtaW5kZXgta2V5LTMyLWJ5dGVz", null, null, null);
    }

    @Test
    void isDeterministicNormalizedAndTenantScoped() {
        var bi = new BlindIndex(props());
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertThat(bi.vin(a, " abc12345678 ")).isEqualTo(bi.vin(a, "ABC12345678"));
        assertThat(bi.vin(a, "ABC12345678")).isNotEqualTo(bi.vin(b, "ABC12345678"));
    }
}
