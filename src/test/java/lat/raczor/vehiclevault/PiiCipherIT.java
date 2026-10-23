package lat.raczor.vehiclevault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import lat.raczor.vehiclevault.crypto.PiiCipher;
import lat.raczor.vehiclevault.crypto.PiiCipher.Pii;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class PiiCipherIT extends AbstractIntegrationTest {

    @Autowired PiiCipher cipher;

    @Test
    void roundTripsThroughRealKmsAndNeverStoresPlaintext() {
        UUID t = UUID.randomUUID(), row = UUID.randomUUID();
        String enc = cipher.encrypt(t, row, new Pii("1HGCM82633A004352", "C456XYZ", "Ana Perez"));
        assertThat(enc).doesNotContain("1HGCM82633A004352", "Ana");
        assertThat(cipher.decrypt(t, row, enc)).isEqualTo(new Pii("1HGCM82633A004352", "C456XYZ", "Ana Perez"));
    }

    @Test
    void nullFieldsRoundTrip() {
        UUID t = UUID.randomUUID(), row = UUID.randomUUID();
        assertThat(cipher.decrypt(t, row, cipher.encrypt(t, row, new Pii("VIN12345678", null, null))).plate()).isNull();
    }

    @Test
    void ciphertextMovedToAnotherRowOrTenantFailsToDecrypt() {
        UUID t = UUID.randomUUID(), row = UUID.randomUUID();
        String enc = cipher.encrypt(t, row, new Pii("VIN12345678", "P1", "Owner"));
        assertThatThrownBy(() -> cipher.decrypt(UUID.randomUUID(), row, enc)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(t, UUID.randomUUID(), enc)).isInstanceOf(IllegalStateException.class);
    }
}
