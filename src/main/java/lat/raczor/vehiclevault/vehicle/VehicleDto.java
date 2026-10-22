package lat.raczor.vehiclevault.vehicle;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class VehicleDto {
    private VehicleDto() {}

    public record Create(@NotBlank String make, @NotBlank String model, @Min(1886) @Max(2100) int year,
                         @NotBlank @Size(min = 11, max = 17) String vin,
                         @Size(max = 20) String plate, @Size(max = 120) String ownerName) {}

    /** PII fields are omitted entirely unless the caller holds vehicle:read-pii. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record View(UUID id, String make, String model, int year, String vin, String plate, String ownerName) {}
}
