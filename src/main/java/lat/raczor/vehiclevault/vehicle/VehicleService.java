package lat.raczor.vehiclevault.vehicle;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lat.raczor.vehiclevault.audit.AuditLog;
import lat.raczor.vehiclevault.authz.PolicyDecisionPoint;
import lat.raczor.vehiclevault.authz.RolePermissionPolicy;
import lat.raczor.vehiclevault.crypto.BlindIndex;
import lat.raczor.vehiclevault.crypto.PiiCipher;
import lat.raczor.vehiclevault.crypto.PiiCipher.Pii;
import lat.raczor.vehiclevault.tenant.TenantContext;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VehicleService {

    private final VehicleRepository repo;
    private final TenantContext tenant;
    private final AuditLog audit;
    private final PiiCipher cipher;
    private final BlindIndex blindIndex;
    private final PolicyDecisionPoint pdp;

    public VehicleService(VehicleRepository repo, TenantContext tenant, PiiCipher cipher, BlindIndex blindIndex,
                   PolicyDecisionPoint pdp, AuditLog audit) {
        this.repo = repo;
        this.tenant = tenant;
        this.cipher = cipher;
        this.blindIndex = blindIndex;
        this.pdp = pdp;
        this.audit = audit;
    }

    @Transactional
    public VehicleDto.View create(VehicleDto.Create in, Authentication auth) {
        UUID tenantId = tenant.currentTenant();
        UUID id = UUID.randomUUID();
        String vin = BlindIndex.normalize(in.vin());
        String piiEnc = cipher.encrypt(tenantId, id, new Pii(vin, in.plate(), in.ownerName()));
        var row = new VehicleRow(id, tenantId, in.make(), in.model(), in.year(), null, piiEnc,
                null, null, null, java.time.OffsetDateTime.now());
        repo.insert(row, blindIndex.vin(tenantId, vin));
        audit.record("vehicle.create", auth, tenantId, id);
        // Answer from the input we already hold: decrypting what we just encrypted would cost a KMS call and
        // log a PII read that never happened.
        boolean pii = pdp.allowed(auth, RolePermissionPolicy.VEHICLE_READ_PII);
        return new VehicleDto.View(id, in.make(), in.model(), in.year(), pii ? vin : null,
                pii ? in.plate() : null, pii ? in.ownerName() : null);
    }

    @Transactional(readOnly = true)
    public Optional<VehicleDto.View> get(UUID id, Authentication auth) {
        return repo.find(id).map(r -> view(r, auth));
    }

    @Transactional(readOnly = true)
    public List<VehicleDto.View> list(Authentication auth) {
        return repo.list(50).stream().map(r -> view(r, auth)).toList();
    }

    @Transactional(readOnly = true)
    public Optional<VehicleDto.View> byVin(String vin, Authentication auth) {
        UUID tenantId = tenant.currentTenant();
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
        audit.record("vehicle.pii.read", auth, r.tenantId(), r.id());
        Pii pii = cipher.decrypt(r.tenantId(), r.id(), r.piiEnc());
        return new VehicleDto.View(r.id(), r.make(), r.model(), r.year(), pii.vin(), pii.plate(), pii.ownerName());
    }
}
