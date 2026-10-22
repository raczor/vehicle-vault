# vehicle-vault

Spring Boot 3 / Java 21 service that stores vehicles and protects their sensitive data. It is a small, runnable reference for three platform-security patterns:

1. **PII field encryption with AWS KMS** (envelope encryption, AES-256-GCM, per-row data keys).
2. **Postgres row-level security** for tenant isolation, enforced by the database, not just by app code.
3. **Centralized authorization**: JWT in, one policy decision point out.

Plus a **zero-downtime backfill** that migrates existing plaintext rows to the encrypted form while the service keeps serving traffic.

Everything runs locally with Docker; [floci](https://github.com/floci-io/floci) stands in for AWS KMS.

## Run it

```bash
brew install docker docker-compose colima && colima start     # once, macOS
docker compose up -d --build                                  # app + postgres + floci
python3 scripts/smoke.py                                      # end-to-end checks (seeds legacy rows, runs the backfill)
mvn test                                                      # unit tests (no Docker needed)
```

Try it by hand:

```bash
T=$(curl -s "localhost:8080/dev/token?tenant=$(uuidgen)&roles=MANAGER")
curl -s -XPOST localhost:8080/api/vehicles -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d '{"make":"Honda","model":"Civic","year":2021,"vin":"1HGCM82633A004352","plate":"C456XYZ","ownerName":"Ana Perez"}'
```

`/dev/token` only exists under the `dev` profile (compose sets it). Roles: `ADMIN`, `MANAGER`, `VIEWER`.

## API

| Method | Path | Permission |
|--------|------|------------|
| POST | `/api/vehicles` | `vehicle:write` |
| GET | `/api/vehicles/{id}` | `vehicle:read` (PII fields only with `vehicle:read-pii`) |
| GET | `/api/vehicles[?vin=...]` | `vehicle:read` (same PII rule) |

## How each piece works

**Encryption** (`crypto/FieldCipher`). Sensitive fields: VIN, plate, owner name. Each row gets its own data key from KMS `GenerateDataKey`; the wrapped key is stored in `dek_wrapped`, fields are AES-GCM encrypted with it. The KMS encryption context and the GCM AAD both carry `tenant` and `vehicle` (row id), so ciphertext moved to another row or tenant fails to decrypt. Unwrapped keys sit in a bounded in-memory LRU so a list of N rows is not N KMS calls. Users without `vehicle:read-pii` never trigger a decrypt.

**Lookup by VIN** (`crypto/BlindIndex`). `vin_bidx` is HMAC-SHA256 over `tenant:VIN`, so exact-match search works without decrypting anything and the same VIN hashes differently per tenant.

**Row-level security** (`V4__vehicles_rls.sql`, `vehicle/TenantSession`). The service connects as `app_user`, which does not own the table and has no `BYPASSRLS`. Each transaction starts with `set_config('app.tenant_id', ..., true)`; the policy filters on it. The SQL has no `WHERE tenant_id = ...` on purpose. With no tenant set the policy matches nothing (fails closed).

**Authorization** (`authz/`). Controllers only ask `@pdp.allowed(authentication, '<permission>')`. The role to permission map lives in one class behind the `PolicyDecisionPoint` interface; replace the implementation to call OPA, Cedar or a central authz service. Local JWTs are HS256; swap the decoder for your IdP's issuer URI in `SecurityConfig`.

## Zero-downtime backfill

Expand, migrate, contract:

1. **Expand** (`V2`, `V3`): add nullable encrypted columns, make `vin` nullable (metadata-only), build indexes `CONCURRENTLY` (no write lock; needs `postgresql.transactional-lock: false` for Flyway). Old code keeps working.
2. **Dual-read deploy**: the new app writes encrypted-only and reads either form. `encrypted_at IS NULL` marks a legacy row, so reads and VIN lookup work before, during and after the backfill.
3. **Backfill** (`backfill/BackfillService`): `docker compose --profile backfill run --rm backfill`. Batches of 100: KMS calls run with no transaction open, then a short transaction applies the updates, with a pause between batches and an `UPDATE ... WHERE encrypted_at IS NULL` guard. Ciphertext is written and plaintext nulled in the same statement, so there is no half-migrated row. Idempotent, resumable, safe to run twice or concurrently. It runs as `backfill_user` (`BYPASSRLS`) since it spans tenants. Tune with `BACKFILL_BATCH_SIZE` and `BACKFILL_PAUSE_MS`.
4. **Contract** (`src/main/resources/db/contract/drop_plaintext.sql`, manual): drops the plaintext columns, and refuses to run while any row is pending. Ship a release that no longer reads those columns first.

## Local-only shortcuts (change before real use)

- The compose bootstrap user `vault_owner` is a Postgres superuser; in production, Flyway should use a plain schema-owner role.
- Passwords, `JWT_SECRET` and `BLIND_INDEX_KEY` have dev defaults. Load them from a secret store; rotating the blind-index key requires recomputing `vin_bidx`.
- `KMS_BOOTSTRAP=true` creates the key alias in floci. In AWS, provision the key and alias with IaC and grant the service role `kms:GenerateDataKey` and `kms:Decrypt` only.
- floci is an emulator; KMS behaviour (key policies, rotation, grants) is not enforced the way AWS enforces it.
- `JWT_SECRET` and `BLIND_INDEX_KEY` have no defaults: the app refuses to start without them. The dev values live in `application-dev.yml`, which only the `dev` profile loads.
- If a deploy is interrupted during `CREATE INDEX CONCURRENTLY`, Postgres leaves an INVALID index and `IF NOT EXISTS` will not rebuild it. Check `pg_index.indisvalid` and drop it before retrying.
- Backfill rows that cannot be migrated (for example a NULL VIN) are logged and skipped; they stay pending and block the contract step.
