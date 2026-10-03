CREATE TYPE seat_status AS ENUM ('available', 'held', 'confirmed');
CREATE TYPE reservation_status AS ENUM ('held', 'confirmed', 'cancelled', 'expired');

CREATE TABLE shows (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name           text        NOT NULL UNIQUE,
    price_paise    bigint      NOT NULL CHECK (price_paise >= 0),
    total_seats    integer     NOT NULL CHECK (total_seats > 0),
    per_user_limit integer     NOT NULL DEFAULT 4 CHECK (per_user_limit > 0),
    hold_ttl_sec   integer     NOT NULL DEFAULT 120 CHECK (hold_ttl_sec > 0),
    sales_open_at  timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE seats (
    show_id         uuid        NOT NULL REFERENCES shows (id) ON DELETE CASCADE,
    seat_no         integer     NOT NULL,          -- THE GLOBAL LOCK ORDER
    label           text        NOT NULL,          -- immutable; lock predicates filter on this
    section         text,                          -- tier name, NULL for base-priced seats
    row_label       text,                          -- reserved for "seats together"
    seat_in_row     integer,
    price_paise     bigint      CHECK (price_paise IS NULL OR price_paise >= 0),
    status          seat_status NOT NULL DEFAULT 'available',
    reservation_id  uuid,                          -- FENCING TOKEN
    owner_id        uuid,
    hold_expires_at timestamptz,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (show_id, seat_no),
    CONSTRAINT seats_label_unique UNIQUE (show_id, label),
    CONSTRAINT seat_shape CHECK (
        (status = 'available' AND reservation_id IS NULL     AND owner_id IS NULL     AND hold_expires_at IS NULL)
     OR (status = 'held'      AND reservation_id IS NOT NULL AND owner_id IS NOT NULL AND hold_expires_at IS NOT NULL)
     OR (status = 'confirmed' AND reservation_id IS NOT NULL AND owner_id IS NOT NULL AND hold_expires_at IS NULL))
);

CREATE INDEX seats_expiring_holds ON seats (hold_expires_at) WHERE status = 'held';

CREATE TABLE user_show_quota (                     -- I2
    show_id      uuid    NOT NULL REFERENCES shows (id) ON DELETE CASCADE,
    user_id      uuid    NOT NULL,
    active_seats integer NOT NULL DEFAULT 0 CHECK (active_seats >= 0),
    PRIMARY KEY (show_id, user_id)
);

CREATE TABLE reservations (
    id           uuid               PRIMARY KEY,
    show_id      uuid               NOT NULL REFERENCES shows (id) ON DELETE CASCADE,
    user_id      uuid               NOT NULL,
    seat_labels  text[]             NOT NULL,
    amount_paise bigint             NOT NULL CHECK (amount_paise >= 0),
    status       reservation_status NOT NULL,
    expires_at   timestamptz,
    created_at   timestamptz        NOT NULL DEFAULT now(),
    updated_at   timestamptz        NOT NULL DEFAULT now()
);

CREATE INDEX reservations_by_user  ON reservations (show_id, user_id);
CREATE INDEX reservations_expiring ON reservations (expires_at) WHERE status = 'held';

CREATE TABLE idempotency_keys (                    -- I3
    user_id       uuid        NOT NULL,
    key           text        NOT NULL,
    scope         text        NOT NULL,            -- 'reserve:<show_id>'  — keeps writes single-shard
    request_hash  bytea       NOT NULL,            -- sha256(show_id || sorted seats || mode)
    status_code   integer     NOT NULL,            -- 0 while owned by the in-flight transaction
    response_body jsonb       NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    expires_at    timestamptz NOT NULL DEFAULT now() + interval '24 hours',
    PRIMARY KEY (user_id, key, scope)
);

CREATE INDEX idempotency_keys_expiry ON idempotency_keys (expires_at);

CREATE TABLE shedlock (
    name       text        PRIMARY KEY,
    lock_until timestamptz NOT NULL,
    locked_at  timestamptz NOT NULL,
    locked_by  text        NOT NULL
);
