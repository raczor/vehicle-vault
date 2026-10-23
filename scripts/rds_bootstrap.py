#!/usr/bin/env python3
"""Prepare the floci RDS instance: create the Postgres roles, then run the Flyway migrations as the owner.

Terraform owns the instance and the credentials, docker/initdb-roles.sql owns the role definitions for plain
compose; on RDS the passwords are random, so this applies the same grants with the secret values.

Usage: python3 scripts/rds_bootstrap.py [--host host.docker.internal] [--port 7001] [--skip-migrate]

The passwords come from the secrets Terraform wrote to Secrets Manager. Migrations are a separate step from the
service on purpose: the service only ever holds the app_user credentials.
"""
import argparse
import json
import subprocess
import urllib.request
from pathlib import Path

ENDPOINT = "http://localhost:4566"


def secret(name):
    """Secrets Manager JSON API. floci accepts the call with dummy credentials."""
    req = urllib.request.Request(
        ENDPOINT, method="POST", data=json.dumps({"SecretId": name}).encode(),
        headers={"Content-Type": "application/x-amz-json-1.1", "X-Amz-Target": "secretsmanager.GetSecretValue",
                 "Authorization": "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/secretsmanager/aws4_request, "
                                  "SignedHeaders=host, Signature=x"})
    with urllib.request.urlopen(req) as r:
        return json.loads(json.load(r)["SecretString"])


def psql(host, port, owner, sql):
    out = subprocess.run(
        ["docker", "run", "--rm", "-i", "-e", f"PGPASSWORD={owner['password']}", "postgres:17", "psql",
         "-h", host, "-p", str(port), "-U", owner["username"], "-d", owner["dbname"], "-v", "ON_ERROR_STOP=1", "-At"],
        input=sql, capture_output=True, text=True)
    if out.returncode:
        raise SystemExit(out.stderr.strip())
    return out.stdout.strip()


def migrate(host, port, owner):
    """Run Flyway as a throwaway container, the same image and flags as the compose migrate service."""
    sql_dir = Path(__file__).resolve().parent.parent / "src/main/resources/db/migration"
    out = subprocess.run(
        ["docker", "run", "--rm", "-v", f"{sql_dir}:/flyway/sql:ro", "flyway/flyway:11",
         f"-url=jdbc:postgresql://{host}:{port}/{owner['dbname']}", f"-user={owner['username']}",
         f"-password={owner['password']}", "-locations=filesystem:/flyway/sql",
         "-postgresql.transactional.lock=false", "-connectRetries=10", "migrate"],
        capture_output=True, text=True)
    if out.returncode:
        raise SystemExit(out.stdout + out.stderr)
    print(out.stdout.strip().splitlines()[-1])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="host.docker.internal")
    ap.add_argument("--port", type=int, default=7001)
    ap.add_argument("--skip-migrate", action="store_true")
    a = ap.parse_args()
    owner, app, backfill = (secret(f"vehicle-vault/db/{n}") for n in ("owner", "app_user", "backfill_user"))
    # Passwords come from Terraform's random_password (alphanumeric), so plain quoting is safe.
    sql = f"""
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user') THEN
    CREATE ROLE app_user LOGIN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'backfill_user') THEN
    CREATE ROLE backfill_user LOGIN BYPASSRLS;
  END IF;
END $$;
ALTER ROLE app_user PASSWORD '{app['password']}';
ALTER ROLE backfill_user PASSWORD '{backfill['password']}';
SELECT current_user, version();
"""
    print(psql(a.host, a.port, owner, sql))
    print("roles ready: app_user, backfill_user")
    if not a.skip_migrate:
        migrate(a.host, a.port, owner)


if __name__ == "__main__":
    main()
