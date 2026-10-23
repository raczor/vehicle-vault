-- EXPAND step. Every statement is additive or metadata-only, so old app versions keep working.
ALTER TABLE vehicles ALTER COLUMN vin DROP NOT NULL;           -- metadata-only
ALTER TABLE vehicles ADD COLUMN pii_enc         text;          -- one AWS Encryption SDK message (vin, plate, owner)
ALTER TABLE vehicles ADD COLUMN vin_bidx        text;          -- HMAC blind index for VIN lookups
ALTER TABLE vehicles ADD COLUMN encrypted_at    timestamptz;   -- NULL = still plaintext, backfill pending
