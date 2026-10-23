package lat.raczor.vehiclevault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class VehicleApiIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private static JwtRequestPostProcessor as(UUID tenant, String role) {
        return jwt().jwt(j -> j.subject("u-" + role.toLowerCase()).claim("tenant_id", tenant.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private String create(UUID tenant) throws Exception {
        String body = mvc.perform(post("/api/vehicles").with(as(tenant, "MANAGER")).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"make":"Honda","model":"Civic","year":2021,"vin":"1hgcm82633a004352",
                                 "plate":"C456XYZ","ownerName":"Ana Perez"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vin").value("1HGCM82633A004352"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asText();
    }

    @Test
    void sensitiveFieldsAreEncryptedAtRestAndDecryptedOnRead() throws Exception {
        UUID tenant = UUID.randomUUID();
        String id = create(tenant);

        mvc.perform(get("/api/vehicles/" + id).with(as(tenant, "MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plate").value("C456XYZ"))
                .andExpect(jsonPath("$.ownerName").value("Ana Perez"));

        try (var c = owner(); var ps = c.prepareStatement(
                "select concat_ws('|', vin, plate, owner_name, pii_enc), "
                        + "pii_enc is not null, vin_bidx is not null from vehicles where id = ?")) {
            ps.setObject(1, UUID.fromString(id));
            ResultSet rs = ps.executeQuery();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).doesNotContain("1HGCM82633A004352", "C456XYZ", "Ana Perez");
            assertThat(rs.getBoolean(2)).isTrue();
            assertThat(rs.getBoolean(3)).isTrue();
        }
    }

    @Test
    void lookupByVinUsesTheBlindIndex() throws Exception {
        UUID tenant = UUID.randomUUID();
        String id = create(tenant);
        mvc.perform(get("/api/vehicles").param("vin", "1HGCM82633A004352").with(as(tenant, "MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id));
    }

    @Test
    void tenantsCannotSeeEachOthersRows() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        String id = create(a);
        mvc.perform(get("/api/vehicles/" + id).with(as(b, "MANAGER"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/vehicles").with(as(b, "MANAGER"))).andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/vehicles").param("vin", "1HGCM82633A004352").with(as(b, "MANAGER")))
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void rlsFailsClosedWhenNoTenantIsBound() throws Exception {
        create(UUID.randomUUID());
        try (var c = connectAs("app_user", "app_user_dev"); var st = c.createStatement();
             ResultSet rs = st.executeQuery("select count(*) from vehicles")) {
            rs.next();
            assertThat(rs.getLong(1)).isZero();
        }
    }

    @Test
    void viewerSeesNoPiiAndCannotWrite() throws Exception {
        UUID tenant = UUID.randomUUID();
        String id = create(tenant);
        mvc.perform(get("/api/vehicles/" + id).with(as(tenant, "VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.make").value("Honda"))
                .andExpect(jsonPath("$.vin").doesNotExist())
                .andExpect(jsonPath("$.plate").doesNotExist())
                .andExpect(jsonPath("$.ownerName").doesNotExist());
        mvc.perform(post("/api/vehicles").with(as(tenant, "VIEWER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"make\":\"x\",\"model\":\"y\",\"year\":2020,\"vin\":\"12345678901\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousIsRejectedAndMalformedTenantIsForbidden() throws Exception {
        mvc.perform(get("/api/vehicles")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/vehicles").with(jwt().jwt(j -> j.claim("tenant_id", "not-a-uuid"))
                        .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void realSignedTokensAreDecodedAndRolesMapToPermissions() throws Exception {
        UUID tenant = UUID.randomUUID();
        String manager = mvc.perform(get("/dev/token").param("tenant", tenant.toString()).param("roles", "MANAGER"))
                .andReturn().getResponse().getContentAsString();
        String viewer = mvc.perform(get("/dev/token").param("tenant", tenant.toString()).param("roles", "VIEWER"))
                .andReturn().getResponse().getContentAsString();
        String body = "{\"make\":\"Kia\",\"model\":\"Rio\",\"year\":2019,\"vin\":\"KNADN512XK6000001\"}";
        mvc.perform(post("/api/vehicles").header("Authorization", "Bearer " + manager)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(post("/api/vehicles").header("Authorization", "Bearer " + viewer)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(get("/api/vehicles").header("Authorization", "Bearer " + manager + "x")).andExpect(status().isUnauthorized());
    }

    @Test
    void piiAccessIsAuditedAndNonPiiReadsAreNot(CapturedOutput output) throws Exception {
        UUID tenant = UUID.randomUUID();
        String id = create(tenant);
        assertThat(output.getAll()).contains("action=vehicle.create subject=u-manager tenant=" + tenant)
                .doesNotContain("action=vehicle.pii.read"); // creating is not reading

        mvc.perform(get("/api/vehicles/" + id).with(as(tenant, "VIEWER"))).andExpect(status().isOk());
        assertThat(output.getAll()).doesNotContain("action=vehicle.pii.read subject=u-viewer");

        mvc.perform(get("/api/vehicles/" + id).with(as(tenant, "MANAGER"))).andExpect(status().isOk());
        assertThat(output.getAll()).contains("action=vehicle.pii.read subject=u-manager tenant=" + tenant + " vehicle=" + id)
                .doesNotContain("1HGCM82633A004352");
    }
}
