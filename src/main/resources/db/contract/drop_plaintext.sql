-- CONTRACT step. NOT run by Flyway. Run by hand (psql) only after the backfill reports 0 pending
-- and every app instance runs a version that no longer reads the plaintext columns.
BEGIN;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM vehicles WHERE encrypted_at IS NULL) THEN
        RAISE EXCEPTION 'refusing to drop plaintext columns: rows still pending backfill';
    END IF;
END $$;
ALTER TABLE vehicles DROP COLUMN vin, DROP COLUMN plate, DROP COLUMN owner_name;
DROP INDEX IF EXISTS vehicles_pending_backfill_idx;
COMMIT;
