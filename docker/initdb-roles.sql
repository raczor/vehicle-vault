-- Runs once on first container start, as the bootstrap superuser (vault_owner).
-- vault_owner owns the schema and runs Flyway. app_user is what the service connects as:
-- it is NOT the table owner and has no BYPASSRLS, so row-level security always applies.
-- backfill_user is only used by the one-off backfill job and bypasses RLS on purpose.
CREATE ROLE app_user LOGIN PASSWORD 'app_user_dev';
CREATE ROLE backfill_user LOGIN PASSWORD 'backfill_user_dev' BYPASSRLS;
