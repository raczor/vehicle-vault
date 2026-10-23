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

**Encryption** (`crypto/PiiCipher`). Sensitive fields (VIN, plate, owner name) are encrypted with the [AWS Encryption SDK](https://docs.aws.amazon.com/encryption-sdk/latest/developer-guide/introduction.html) instead of hand-written crypto. Each row is one self-describing, versioned message stored in `pii_enc`: the SDK asks KMS for a data key (`GenerateDataKey`), encrypts locally with AES-256-GCM, and embeds the wrapped key, the algorithm and the encryption context. The context is `{tenant, vehicle}`; the SDK authenticates it and checks it on decrypt, so a message copied to another row or tenant fails. The client uses the `RequireEncryptRequireDecrypt` commitment policy and an unsigned committing suite (the default suite adds a third context key for signing, which would not match the IAM condition). Users without `vehicle:read-pii` never trigger a decrypt.

**Lookup by VIN** (`crypto/BlindIndex`). `vin_bidx` is HMAC-SHA256 over `tenant:VIN`, so exact-match search works without decrypting anything and the same VIN hashes differently per tenant.

**Row-level security** (`V4__vehicles_rls.sql`, `tenant/TenantContext`). The service connects as `app_user`, which does not own the table and has no `BYPASSRLS`. A custom transaction manager (`tenant/TenantBindingTransactionManager`) runs `set_config('app.tenant_id', ..., true)` as every transaction begins, so no service method can forget it; the policy filters on it. The SQL has no `WHERE tenant_id = ...` on purpose. With no tenant set the policy matches nothing (fails closed).

**Authorization** (`authz/`). Controllers only ask `@pdp.allowed(authentication, '<permission>')`. The role to permission map lives in one class behind the `PolicyDecisionPoint` interface; replace the implementation to call OPA, Cedar or a central authz service. Local JWTs are HS256 with a shared secret. Set `OIDC_JWK_SET_URI` (and `OIDC_ISSUER`, `OIDC_AUDIENCE`, `OIDC_ROLES_CLAIM`) to validate tokens against an identity provider's public keys instead; the shared secret is then ignored. Tested against a local JWKS server, not a real identity provider.

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

## Infrastructure as code (OpenTofu + Terragrunt)

`infra/` builds the AWS pieces with [OpenTofu](https://opentofu.org) (same HCL as Terraform), organised with [Terragrunt](https://terragrunt.gruntwork.io) so each component is defined once and each environment only supplies what differs. The same modules apply to **floci** locally and to real AWS.

```bash
brew install opentofu terragrunt
```

```
infra/
  modules/               plain OpenTofu modules (the building blocks)
    iam/                 app role (assumed by the service) and key-admin role (manages the key, can never use it)
    kms/                 customer managed key + alias, rotation on, a key policy with three statements (see below)
    app_access/          least-privilege IAM policies attached to those roles
    network/             VPC, two private subnets, security groups (db reachable only from the app group)
    database/            RDS Postgres 17, parameter group (force TLS, log DDL), 3 Secrets Manager secrets
  terragrunt/
    root.hcl             generates provider, versions and state config for every unit; pins the tofu binary
    _envcommon/          each component defined once: module source, dependencies, inputs
    local/               floci: env.hcl (endpoints, short deletion windows) + one 6-line leaf per component
    aws/                 real AWS: env.hcl (30 day key deletion, deletion protection, 7 day backups) + leaves
```

How it fits: a leaf like `local/database/terragrunt.hcl` only says "include `root.hcl` and `_envcommon/database.hcl`". `_envcommon/database.hcl` names the module, declares `dependency` blocks on `network` and `rds-key`, and maps their outputs to inputs. Terragrunt reads those dependencies to apply in the right order (iam, then network and the keys in parallel, then database, then app-access), and each leaf keeps its own small state file. Everything that differs between environments is in `env.hcl`.

Run the whole thing locally:

```bash
export COMPOSE_FILE=compose.yaml:compose.aws.yaml   # opt-in: mounts the Docker socket so floci can start RDS
docker compose up -d floci
(cd infra/terragrunt/local && terragrunt run --all apply)   # key, roles, policies, VPC, RDS, secrets
python3 scripts/rds_bootstrap.py                    # creates app_user / backfill_user, then runs the Flyway migrate job
docker compose --profile aws up -d --build app-aws  # the service itself does not migrate
python3 scripts/smoke.py --aws                      # the same 18 end-to-end checks, against this stack
```

Useful variations: `terragrunt run --all plan`, `terragrunt run --all destroy`, or run one component with `cd infra/terragrunt/local/pii-key && terragrunt apply` (its dependencies must already be applied). For `aws/`, `AWS_ACCOUNT_ID` is required (there is deliberately no default, so a wrong account cannot slip into the key policy) and `AWS_REGION` defaults to `us-east-1`. CI sets a placeholder account id because `validate` never calls AWS.

What each layer teaches:

- **Key policy vs IAM policy.** KMS needs *both* to allow an action. The key policy (`modules/kms`) does three things: delegates to IAM (`EnableIamPolicies`), hard-denies the admin role any cryptographic use (`AdminsCannotUseTheKey`, separation of duties), and denies `Decrypt`/`GenerateDataKey` when no encryption context is sent (`RequireEncryptionContext`). The IAM policy (`modules/app_access`) gives the service only those two actions, on that one key, and only when the context keys are exactly `tenant` and `vehicle`, the same keys `PiiCipher` sends.
- **Two keys.** The PII key encrypts fields. A separate key encrypts the RDS volume and snapshots. Compromising or losing one does not affect the other.
- **Terraform owns infrastructure, Flyway owns schema.** Terraform creates the instance and credentials; the `vehicles` table, indexes and RLS policy come from `src/main/resources/db/migration` when the app starts. Postgres roles are applied by `scripts/rds_bootstrap.py` (floci) or `docker/initdb-roles.sql` (plain compose).
- **Credentials from Secrets Manager.** Terraform generates the passwords (`random_password`) and stores them as JSON secrets, one per role. With `DB_SECRET_ID` set, the app reads its own (`app_user`) secret at startup (`SecretsManagerEnvironmentPostProcessor`), which is exactly what the `ReadOwnSecrets` IAM statement permits. Note the state file also contains the passwords: in real use put state in an encrypted remote backend.

**What floci does NOT do** (so you do not learn the wrong lesson): it stores key policies and IAM policies but does not enforce them. A `GenerateDataKey` without an encryption context succeeds locally and would be denied by real AWS. Treat the local stack as a way to check that the resources and the app wiring work, and use real AWS (or the IAM policy simulator) to verify that permissions deny what they should. `envs/aws` is validated in CI but never applied by this repo.

Before pointing the service at real RDS: the parameter group sets `rds.force_ssl=1`, so the JDBC URL needs `?sslmode=require` (or `verify-full` with the RDS CA bundle). Floci does not enforce TLS, so the local stack will not warn you. The app security group also needs outbound 443 to KMS and Secrets Manager (or VPC endpoints); only the 5432 path to the database is created here.

Who reads which secret: the `app` role's IAM policy grants only the `app_user` secret, and that is all the service reads. Migrations run as a separate job (the `migrate` compose service, or `scripts/rds_bootstrap.py` for floci RDS) with the owner credentials. In real AWS give that job and the one-off backfill job their own roles for the `owner` and `backfill_user` secrets; neither role is created here.

## Architecture: what we have and what is missing

| Piece | Status | Where it stands |
|-------|--------|-----------------|
| Field encryption | Have | AWS Encryption SDK, committing suite, encryption context |
| Tenant isolation | Have | Postgres RLS, non-owner role, bound per transaction automatically, fails closed |
| Authentication | Partial | OIDC mode (JWKS + issuer) tested locally; no real identity provider wired in |
| Authorization | Partial | One permission interface and role map in code; a policy engine (OPA, Cedar, Verified Permissions) is the next step |
| Key management | Have | Two keys, rotation on, key-admin cannot use the key, context required by policy |
| Migrations | Have | Separate Flyway job as the schema owner; expand, migrate, contract |
| Infrastructure as code | Partial | Validated in CI; state is local and nothing plans or applies from CI |
| Audit | Partial | Structured `AUDIT` log lines only; not shipped, not tamper-resistant |
| Database credentials | Partial | Read from Secrets Manager at startup; no rotation |
| Data key caching | Missing | Every decrypting read is one KMS `Decrypt` (50 rows is 50 calls) |

Not built here, and called out so nobody assumes they exist:

- **Rotate DB passwords:** Secrets Manager rotation on a schedule, plus a restart or periodic re-read in the service.
- **Rotate the blind-index key:** requires recomputing `vin_bidx` for every row, i.e. another backfill job.
- **Plan and apply from CI:** `plan` on each PR, `apply` from a protected pipeline with an assumed role, encrypted remote state with locking, drift detection, policy scanning.
- **Audit shipping and alerting:** write-once storage for the `AUDIT` lines and CloudTrail, alarms on denied KMS calls and unusual PII-read volume.
- **Network and database hardening:** VPC endpoints, IAM database authentication, Multi-AZ, tested restores, TLS in the JDBC URL.
- **Data lifecycle:** retention and erasure, including backups.
- **Supply chain:** image signing and an SBOM (CodeQL and dependency review are in place).
- **Load and failure testing:** KMS quotas and what happens when KMS is unreachable.

Note: the `V2` migration was rewritten (one `pii_enc` column instead of four) before any deployment existed. After a release, schema changes must be new migrations, never edits. If you ran an earlier version locally, your compose database still has the old checksum and the migrate job will refuse to run: `docker compose down -v` resets it.

Known limit: `PiiCipher` resolves the key alias to one key ARN at startup and decrypts only with that key. Annual KMS rotation keeps the same key id, so it is unaffected, but replacing the key with a new one behind the alias would leave old rows undecryptable until the cipher is taught to accept both ARNs.
