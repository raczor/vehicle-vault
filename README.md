# vehicle-vault

Spring Boot 3 / Java 21 service that stores vehicles and protects their sensitive data. It is a small, runnable reference for three platform-security patterns:

1. **PII field encryption with AWS KMS** (envelope encryption, AES-256-GCM, per-row data keys).
2. **Postgres row-level security** for tenant isolation, enforced by the database, not just by app code.
3. **Centralized authorization**: JWT in, one policy decision point out.

Plus a **zero-downtime backfill** that migrates existing plaintext rows to the encrypted form while the service keeps serving traffic.

Docs site (concepts like RLS and envelope encryption explained, plus the infra walkthrough): https://raczor.github.io/vehicle-vault/

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

## Tests and CI

```bash
mvn test      # unit tests, no Docker
mvn verify    # + Testcontainers ITs against real Postgres and floci (RLS, authz, encryption, backfill)
```

On macOS with Colima, point Testcontainers at its socket first:

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

GitHub Actions: `ci.yml` (`mvn verify`), `codeql.yml` (CodeQL, `security-extended`), `dependency-review.yml` (blocks PRs adding high-severity vulnerable dependencies), and Dependabot for Maven, Docker and Actions. Secret scanning and push protection are repository settings.

## Infrastructure as code (OpenTofu / Terraform)

`infra/` builds the AWS pieces with [OpenTofu](https://opentofu.org) (same HCL as Terraform: `brew install opentofu`). The same modules apply to **floci** locally and to real AWS.

```
infra/
  modules/
    iam/         app role (assumed by the service) and key-admin role (manages the key, can never use it)
    kms/         customer managed key + alias, rotation on, a key policy with three statements (see below)
    app_access/  least-privilege IAM policies attached to those roles
    network/     VPC, two private subnets, security groups (db reachable only from the app group)
    database/    RDS Postgres 17, parameter group (force TLS, log DDL), 3 Secrets Manager secrets
  envs/
    local/       floci endpoints, short deletion windows, no deletion protection
    aws/         real provider, 30 day key deletion window, deletion protection, 7 day backups (validate only)
```

Run the whole thing locally:

```bash
export COMPOSE_FILE=compose.yaml:compose.aws.yaml   # opt-in: mounts the Docker socket so floci can start RDS
docker compose up -d floci
cd infra/envs/local && tofu init && tofu apply   # key, roles, policies, VPC, RDS, secrets
cd ../../.. && python3 scripts/rds_bootstrap.py  # creates app_user / backfill_user with the secret passwords
docker compose --profile aws up -d --build app-aws   # Flyway creates the tables on that RDS instance
python3 scripts/smoke.py --aws                   # the same 18 end-to-end checks, against this stack
```

What each layer teaches:

- **Key policy vs IAM policy.** KMS needs *both* to allow an action. The key policy (`modules/kms`) does three things: delegates to IAM (`EnableIamPolicies`), hard-denies the admin role any cryptographic use (`AdminsCannotUseTheKey`, separation of duties), and denies `Decrypt`/`GenerateDataKey` when no encryption context is sent (`RequireEncryptionContext`). The IAM policy (`modules/app_access`) gives the service only those two actions, on that one key, and only when the context keys are exactly `tenant` and `vehicle`, the same keys `FieldCipher` sends.
- **Two keys.** The PII key encrypts fields. A separate key encrypts the RDS volume and snapshots. Compromising or losing one does not affect the other.
- **Terraform owns infrastructure, Flyway owns schema.** Terraform creates the instance and credentials; the `vehicles` table, indexes and RLS policy come from `src/main/resources/db/migration` when the app starts. Postgres roles are applied by `scripts/rds_bootstrap.py` (floci) or `docker/initdb-roles.sql` (plain compose).
- **Credentials from Secrets Manager.** Terraform generates the passwords (`random_password`) and stores them as JSON secrets, one per role. With `DB_SECRET_ID` / `FLYWAY_SECRET_ID` set, the app reads them at startup (`SecretsManagerEnvironmentPostProcessor`), which is exactly what the `ReadOwnSecrets` IAM statement permits. Note the state file also contains the passwords: in real use put state in an encrypted remote backend.

**What floci does NOT do** (so you do not learn the wrong lesson): it stores key policies and IAM policies but does not enforce them. A `GenerateDataKey` without an encryption context succeeds locally and would be denied by real AWS. Treat the local stack as a way to check that the resources and the app wiring work, and use real AWS (or the IAM policy simulator) to verify that permissions deny what they should. `envs/aws` is validated in CI but never applied by this repo.

Before pointing the service at real RDS: the parameter group sets `rds.force_ssl=1`, so the JDBC URL needs `?sslmode=require` (or `verify-full` with the RDS CA bundle). Floci does not enforce TLS, so the local stack will not warn you. The app security group also needs outbound 443 to KMS and Secrets Manager (or VPC endpoints); only the 5432 path to the database is created here.

Who reads which secret: the `app` role's IAM policy grants only the `app_user` secret. The local `app-aws` service also reads the `owner` secret so Flyway can migrate at startup, which is a shortcut. In real AWS run migrations as a separate pipeline step or job with its own role allowed to read `owner`, and give the one-off backfill job its own role for the `backfill_user` secret. Neither is created here.
