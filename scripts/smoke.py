#!/usr/bin/env python3
"""End-to-end check against the running compose stack: encryption, RLS, authz, and the legacy backfill.

Usage: docker compose up -d --build && python3 scripts/smoke.py
"""
import json
import subprocess
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASE = "http://localhost:8080"
TENANT_A, TENANT_B = str(uuid.uuid4()), str(uuid.uuid4())
failures = []


def check(name, cond):
    print(("PASS " if cond else "FAIL ") + name)
    if not cond:
        failures.append(name)


def http(method, path, token=None, body=None):
    req = urllib.request.Request(BASE + path, method=method, data=json.dumps(body).encode() if body else None)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw.startswith(("{", "[")) else raw)
    except urllib.error.HTTPError as e:
        return e.code, None


def token(tenant, roles):
    return http("GET", f"/dev/token?tenant={tenant}&roles={roles}")[1]


def sql(query, role="vault_owner"):
    out = subprocess.run(
        ["docker", "compose", "exec", "-T", "postgres", "psql", "-U", role, "-d", "vault", "-At", "-c", query],
        cwd=ROOT, capture_output=True, text=True, check=True)
    return out.stdout.strip()


def main():
    mgr_a, view_a, mgr_b = token(TENANT_A, "MANAGER"), token(TENANT_A, "VIEWER"), token(TENANT_B, "MANAGER")

    # Seed legacy plaintext rows directly (as the schema owner, which bypasses RLS as superuser).
    legacy_id = str(uuid.uuid4())
    sql(f"insert into vehicles (id, tenant_id, make, model, year, vin, plate, owner_name) values "
        f"('{legacy_id}', '{TENANT_A}', 'Toyota', 'Hilux', 2018, 'legacyvin0000001', 'P123ABC', 'Legacy Owner')")
    sql(f"insert into vehicles (id, tenant_id, make, model, year, vin) "
        f"select gen_random_uuid(), '{TENANT_A}', 'Fill', 'Row', 2020, 'FILLVIN' || lpad(g::text, 9, '0') "
        f"from generate_series(1, 250) g")

    # Encrypted create + decrypted read
    status, v = http("POST", "/api/vehicles", mgr_a,
                     {"make": "Honda", "model": "Civic", "year": 2021, "vin": "1hgcm82633a004352",
                      "plate": "C456XYZ", "ownerName": "Ana Perez"})
    check("manager creates vehicle (201)", status == 201)
    vid = v["id"]
    check("response returns the VIN normalized", v["vin"] == "1HGCM82633A004352")
    status, got = http("GET", f"/api/vehicles/{vid}", mgr_a)
    check("manager reads decrypted PII", status == 200 and got["plate"] == "C456XYZ" and got["ownerName"] == "Ana Perez")

    stored = sql(f"select concat_ws('|', vin, plate, owner_name, vin_enc, plate_enc, owner_name_enc) from vehicles where id='{vid}'")
    check("new row has no plaintext in the DB", all(s not in stored for s in ("1HGCM82633A004352", "C456XYZ", "Ana Perez")))
    check("new row has ciphertext + wrapped key",
          sql(f"select (vin_enc is not null and dek_wrapped is not null and vin_bidx is not null) from vehicles where id='{vid}'") == "t")

    # Blind index lookup
    status, found = http("GET", "/api/vehicles?vin=1HGCM82633A004352", mgr_a)
    check("lookup by VIN via blind index", status == 200 and len(found) == 1 and found[0]["id"] == vid)

    # Authorization
    status, seen = http("GET", f"/api/vehicles/{vid}", view_a)
    check("viewer sees the vehicle but no PII", status == 200 and "vin" not in seen and "plate" not in seen and seen["make"] == "Honda")
    check("viewer cannot create (403)", http("POST", "/api/vehicles", view_a,
                                             {"make": "x", "model": "y", "year": 2020, "vin": "12345678901"})[0] == 403)
    check("no token is rejected (401)", http("GET", "/api/vehicles")[0] == 401)

    # Row-level security
    check("other tenant cannot read the row (404)", http("GET", f"/api/vehicles/{vid}", mgr_b)[0] == 404)
    check("other tenant's list is empty", http("GET", "/api/vehicles", mgr_b)[1] == [])
    check("RLS fails closed with no tenant set", sql("select count(*) from vehicles", role="app_user") == "0")

    # Legacy rows keep working before the backfill
    status, legacy = http("GET", f"/api/vehicles/{legacy_id}", mgr_a)
    check("legacy plaintext row still readable pre-backfill", status == 200 and legacy["plate"] == "P123ABC")
    pending = int(sql("select count(*) from vehicles where encrypted_at is null"))
    check(f"backfill has work to do ({pending} rows)", pending == 251)

    # Backfill
    subprocess.run(["docker", "compose", "--profile", "backfill", "run", "--rm", "backfill"], cwd=ROOT, check=True)
    check("backfill left nothing pending", sql("select count(*) from vehicles where encrypted_at is null") == "0")
    check("backfill nulled all plaintext",
          sql("select count(*) from vehicles where vin is not null or plate is not null or owner_name is not null") == "0")
    status, migrated = http("GET", f"/api/vehicles/{legacy_id}", mgr_a)
    check("migrated legacy row decrypts to the same data",
          status == 200 and migrated["plate"] == "P123ABC" and migrated["ownerName"] == "Legacy Owner"
          and migrated["vin"] == "LEGACYVIN0000001")
    status, found = http("GET", "/api/vehicles?vin=legacyvin0000001", mgr_a)
    check("migrated row found via blind index", status == 200 and len(found) == 1)

    print(f"\n{len(failures)} failure(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
