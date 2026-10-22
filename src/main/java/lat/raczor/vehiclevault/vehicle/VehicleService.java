package lat.raczor.vehiclevault.vehicle;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lat.raczor.vehiclevault.authz.PolicyDecisionPoint;
import lat.raczor.vehiclevault.authz.RolePermissionPolicy;
import lat.raczor.vehiclevault.crypto.BlindIndex;
import lat.raczor.vehiclevault.crypto.FieldCipher;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VehicleService {

    private final VehicleRepository repo;
    private final TenantSession tenant;
    private final FieldCipher cipher;
    private final BlindIndex blindIndex;
    private final PolicyDecisionPoint pdp;

    public VehicleService(VehicleRepository repo, TenantSession tenant, FieldCipher cipher, BlindIndex blindIndex,
                   PolicyDecisionPoint pdp) {
        this.repo = repo;
        this.tenant = tenant;
        this.cipher = cipher;
        this.blindIndex = blindIndex;
        this.pdp = pdp;
    }

    @Transactional
    public VehicleDto.View create(VehicleDto.Create in, Authentication auth) {
        UUID tenantId = tenant.bind();
        UUID id = UUID.randomUUID();
        var key = cipher.newRowKey(tenantId, id);
        String vin = BlindIndex.normalize(in.vin());
        var row = new VehicleRow(id, tenantId, in.make(), in.model(), in.year(), null, key.wrapped(),
                key.encrypt(vin), key.encrypt(in.plate()), key.encrypt(in.ownerName()), null, null, null, java.time.OffsetDateTime.now());
        repo.insert(row, blindIndex.vin(tenantId, vin));
        return view(row, auth);
    }

    @Transactional(readOnly = true)
    public Optional<VehicleDto.View> get(UUID id, Authentication auth) {
        tenant.bind();
        return repo.find(id).map(r -> view(r, auth));
    }

    @Transactional(readOnly = true)
    public List<VehicleDto.View> list(Authentication auth) {
        tenant.bind();
        return repo.list(50).stream().map(r -> view(r, auth)).toList();
    }

    @Transactional(readOnly = true)
    public Optional<VehicleDto.View> byVin(String vin, Authentication auth) {
        UUID tenantId = tenant.bind();
        return repo.findByVin(blindIndex.vin(tenantId, vin), BlindIndex.normalize(vin)).map(r -> view(r, auth));
    }

    /** Only decrypts when the policy allows it, so unauthorized callers never trigger a KMS call. */
    private VehicleDto.View view(VehicleRow r, Authentication auth) {
        if (!pdp.allowed(auth, RolePermissionPolicy.VEHICLE_READ_PII)) {
            return new VehicleDto.View(r.id(), r.make(), r.model(), r.year(), null, null, null);
        }
        if (r.encryptedAt() == null) { // legacy row, backfill has not reached it yet
            return new VehicleDto.View(r.id(), r.make(), r.model(), r.year(), r.vinPlain(), r.platePlain(), r.ownerNamePlain());
        }
        var key = cipher.openRowKey(r.dekWrapped(), r.tenantId(), r.id());
        return new VehicleDto.View(r.id(), r.make(), r.model(), r.year(),
                key.decrypt(r.vinEnc()), key.decrypt(r.plateEnc()), key.decrypt(r.ownerNameEnc()));
    }
}
