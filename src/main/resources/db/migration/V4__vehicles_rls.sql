-- Row-level security. FORCE applies it to the table owner too; only BYPASSRLS roles skip it.
ALTER TABLE vehicles ENABLE ROW LEVEL SECURITY;
ALTER TABLE vehicles FORCE ROW LEVEL SECURITY;

-- current_setting(..., true) returns NULL when unset, so a request with no tenant sees nothing (fails closed).
CREATE POLICY vehicles_tenant_isolation ON vehicles
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON vehicles TO app_user;
GRANT SELECT, UPDATE ON vehicles TO backfill_user;
