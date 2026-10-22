-- Original schema: sensitive fields stored in plaintext (the state we are migrating away from).
CREATE TABLE vehicles (
    id          uuid PRIMARY KEY,
    tenant_id   uuid        NOT NULL,
    make        text        NOT NULL,
    model       text        NOT NULL,
    year        integer     NOT NULL,
    vin         text        NOT NULL,
    plate       text,
    owner_name  text,
    created_at  timestamptz NOT NULL DEFAULT now()
);
