-- CONCURRENTLY: no write lock on a live table. Needs to run outside a transaction (see .conf).
CREATE INDEX CONCURRENTLY IF NOT EXISTS vehicles_tenant_vin_bidx_idx ON vehicles (tenant_id, vin_bidx);
-- Matches the legacy branch of the VIN lookup (upper(vin) on rows not yet encrypted); shrinks to nothing as the backfill proceeds.
CREATE INDEX CONCURRENTLY IF NOT EXISTS vehicles_tenant_vin_legacy_idx ON vehicles (tenant_id, upper(vin)) WHERE encrypted_at IS NULL;
-- Lets the backfill find pending rows without scanning the whole table.
CREATE INDEX CONCURRENTLY IF NOT EXISTS vehicles_pending_backfill_idx ON vehicles (id) WHERE encrypted_at IS NULL;
