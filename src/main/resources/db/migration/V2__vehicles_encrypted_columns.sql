-- EXPAND step. Every statement is additive or metadata-only, so old app versions keep working.
ALTER TABLE vehicles ALTER COLUMN vin DROP NOT NULL;           -- metadata-only
ALTER TABLE vehicles ADD COLUMN dek_wrapped     text;          -- KMS-wrapped per-row data key
ALTER TABLE vehicles ADD COLUMN vin_enc         text;
ALTER TABLE vehicles ADD COLUMN plate_enc       text;
ALTER TABLE vehicles ADD COLUMN owner_name_enc  text;
ALTER TABLE vehicles ADD COLUMN vin_bidx        text;          -- HMAC blind index for VIN lookups
ALTER TABLE vehicles ADD COLUMN encrypted_at    timestamptz;   -- NULL = still plaintext, backfill pending
