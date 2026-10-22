package lat.raczor.vehiclevault.vehicle;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {

    private final VehicleService service;

    public VehicleController(VehicleService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("@pdp.allowed(authentication, 'vehicle:write')")
    public ResponseEntity<VehicleDto.View> create(@Valid @RequestBody VehicleDto.Create in, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(in, auth));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@pdp.allowed(authentication, 'vehicle:read')")
    public ResponseEntity<VehicleDto.View> get(@PathVariable UUID id, Authentication auth) {
        return service.get(id, auth).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    @PreAuthorize("@pdp.allowed(authentication, 'vehicle:read')")
    public List<VehicleDto.View> list(@RequestParam(required = false) String vin, Authentication auth) {
        if (vin != null) {
            return service.byVin(vin, auth).map(List::of).orElse(List.of());
        }
        return service.list(auth);
    }
}
