package lat.raczor.vehiclevault;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** Real Postgres (with the app/backfill roles) and real floci KMS, shared by every IT in the JVM. */
@ActiveProfiles("dev") // exposes /dev/token so ITs can exercise the real JWT decoder and role mapping
public abstract class AbstractIntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("vault")
            .withUsername("vault_owner")
            .withPassword("vault_owner_dev")
            .withCopyFileToContainer(MountableFile.forHostPath("docker/initdb-roles.sql"),
                    "/docker-entrypoint-initdb.d/01-roles.sql");

    static final GenericContainer<?> FLOCI = new GenericContainer<>("floci/floci:latest").withExposedPorts(4566);

    static {
        POSTGRES.start();
        FLOCI.start();
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> "app_user");   // never the owner: the owner would not prove RLS
        r.add("spring.datasource.password", () -> "app_user_dev");
        r.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user", () -> "vault_owner");
        r.add("spring.flyway.password", () -> "vault_owner_dev");
        r.add("vault.kms.endpoint", () -> "http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4566));
        r.add("vault.kms.bootstrap", () -> "true");
        r.add("vault.jwt-secret", () -> "integration-test-secret-0123456789abcdef");
        r.add("vault.blind-index-key", () -> "aW50ZWdyYXRpb24tdGVzdC1ibGluZC1pbmRleC1rZXk=");
    }

    protected static Connection connectAs(String user, String password) throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), user, password);
    }

    /** Superuser connection: bypasses RLS, used to seed legacy rows and to inspect what is really stored. */
    protected static Connection owner() throws SQLException {
        return connectAs("vault_owner", "vault_owner_dev");
    }
}
