package lat.raczor.vehiclevault;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class VehicleVaultApplication {
    public static void main(String[] args) {
        SpringApplication.run(VehicleVaultApplication.class, args);
    }
}
