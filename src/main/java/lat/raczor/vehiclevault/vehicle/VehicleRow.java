package lat.raczor.vehiclevault.vehicle;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Raw DB row. Sensitive values are either one encrypted message (piiEnc) or, for not-yet-backfilled rows, plaintext. */
public record VehicleRow(UUID id, UUID tenantId, String make, String model, int year, OffsetDateTime createdAt,
                         String piiEnc, String vinPlain, String platePlain, String ownerNamePlain,
                         OffsetDateTime encryptedAt) {}
