-- AIsolate's own database: what the AIsolate UI defines. The backend owns it and runs this migration;
-- no business data lives here. A space or airlock id is a URL slug that also names Kubernetes objects,
-- so it may be two characters (the airlock hr) and never ends in a hyphen.

-- A space: one team's or product's container for its airlocks. Its definition names who may use every
-- airlock of it and the LLMs and data sources they inherit, as flat JSON like an airlock's form.
CREATE TABLE spaces (
    id          text        PRIMARY KEY CHECK (id ~ '^[a-z]([a-z0-9-]{0,39}[a-z0-9])$'),
    name        text        NOT NULL,
    description text        NOT NULL DEFAULT '',
    created_by  text,
    created_by_name text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    definition  jsonb       NOT NULL DEFAULT '{}'
);

-- An airlock's id is its endpoint path, so it is unique across spaces. Its definition is the rest of its
-- form as flat JSON, saved in place (revision counts the saves): its own users, callers and machine
-- clients, its LLMs and data sources, and what runs, which a deploy brings: the LLM that answers, the
-- prompt, the tools and the handlers. What the code its deploy brought holds: the jar's (a Python
-- project's zip, jar_language python) digest and size, and the tools, handlers and rule sets read from
-- it. The bytes are in airlock_code.
CREATE TABLE airlocks (
    id          text        PRIMARY KEY CHECK (id ~ '^[a-z]([a-z0-9-]{0,39}[a-z0-9])$'),
    space_id    text        NOT NULL REFERENCES spaces (id),
    name        text        NOT NULL,
    description text        NOT NULL DEFAULT '',
    created_by  text,
    created_by_name text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    definition  jsonb       NOT NULL DEFAULT '{}',
    revision    int         NOT NULL DEFAULT 1,
    jar_digest   text,
    jar_size     bigint,
    jar_tools    jsonb,
    jar_handlers jsonb,
    jar_rules    jsonb,
    jar_language text       CHECK (jar_language IN ('java', 'python'))
);

-- The install's LLMs and data sources, the admin's: every space inherits them, and a space or an airlock
-- that defines the same id replaces it. An LLM has no type: it chats or embeds as its model can. A data
-- source's credentials are in the Secret secret_ref names.
CREATE TABLE llms (
    id          text        PRIMARY KEY CHECK (id ~ '^[a-z0-9][a-z0-9_-]{0,40}$'),
    provider    text        NOT NULL,
    url         text        NOT NULL,
    model       text        NOT NULL,
    secret_ref  text,
    created_by  text,
    created_by_name text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE data_sources (
    id          text        PRIMARY KEY CHECK (id ~ '^[a-z0-9][a-z0-9_-]{0,40}$'),
    url         text        NOT NULL,
    secret_ref  text,
    created_by  text,
    created_by_name text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- An airlock's code bytes in 1 MiB chunks, numbered from 0: written and read a chunk at a time, so neither
-- a deploy nor a pod's fetch holds the artifact in memory, and no size ceiling (bytea's is 1 GB a value).
CREATE TABLE airlock_code (
    airlock_id text  NOT NULL REFERENCES airlocks (id) ON DELETE CASCADE,
    seq        int   NOT NULL,
    bytes      bytea NOT NULL,
    PRIMARY KEY (airlock_id, seq)
);

-- The audit trail: one row per audit line an airlock pod handed over (docs/audit.md). Append-only: the
-- backend writes and reads it as aisolate_audit_writer, which may INSERT and SELECT and nothing else, and
-- a trigger refuses UPDATE, DELETE and TRUNCATE whoever asks. Only whole months leave, dropped as
-- partitions by the retention job (the owner). Per airlock, hash = sha256(prev_hash || body): a line
-- changed or taken out breaks the chain GET /v1/audit/verify walks. body is the canonical JSON the hash
-- covers; the columns are read from it, for the filters, and verify checks they still agree with it.
CREATE TABLE audit_events (
    id                bigint      GENERATED ALWAYS AS IDENTITY,
    received_at       timestamptz NOT NULL DEFAULT now(),
    ts                timestamptz NOT NULL,
    space             text        NOT NULL,
    airlock           text        NOT NULL,
    revision          text,
    event             text        NOT NULL CHECK (event IN ('exchange', 'refusal')),
    trace_id          text,
    user_email        text,
    client            text,
    outcome           text        NOT NULL CHECK (outcome IN ('answered', 'dont_know', 'refused', 'error')),
    reason            text,
    question          text,
    answer            text,
    figures_sourced   int,
    figures_computed  int,
    figures_unsourced int,
    figures_mismatch  int,
    input_tokens      int,
    output_tokens     int,
    duration_ms       bigint,
    body              text        NOT NULL,
    digest            text        NOT NULL,
    prev_hash         text        NOT NULL,
    hash              text        NOT NULL,
    PRIMARY KEY (id, received_at)
) PARTITION BY RANGE (received_at);

CREATE INDEX audit_events_chain ON audit_events (space, airlock, id);
CREATE INDEX audit_events_digest ON audit_events (digest);
CREATE INDEX audit_events_trace ON audit_events (trace_id);

CREATE FUNCTION audit_events_refuse() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only: % refused', TG_OP USING ERRCODE = 'insufficient_privilege';
END $$;
CREATE TRIGGER audit_events_append_only BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_refuse();
CREATE TRIGGER audit_events_no_truncate BEFORE TRUNCATE ON audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION audit_events_refuse();

-- The month holding `day`, in UTC, as its own partition audit_events_YYYY_MM; nothing when it exists.
CREATE FUNCTION audit_events_partition(day date) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    first date := date_trunc('month', day)::date;
    part text := 'audit_events_' || to_char(first, 'YYYY_MM');
BEGIN
    EXECUTE format('CREATE TABLE IF NOT EXISTS %I PARTITION OF audit_events FOR VALUES FROM (%L) TO (%L)',
        part, first::timestamp AT TIME ZONE 'UTC', (first + interval '1 month')::timestamp AT TIME ZONE 'UTC');
    -- The row triggers reach a partition from its parent; TRUNCATE of the partition itself does not.
    EXECUTE format('CREATE OR REPLACE TRIGGER audit_events_no_truncate BEFORE TRUNCATE ON %I'
        ' FOR EACH STATEMENT EXECUTE FUNCTION audit_events_refuse()', part);
END $$;

-- Retention: drops every month before the one holding `keep_from`; returns how many.
CREATE FUNCTION audit_events_drop_before(keep_from date) RETURNS int LANGUAGE plpgsql AS $$
DECLARE
    part text;
    dropped int := 0;
BEGIN
    FOR part IN
        SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
        WHERE i.inhparent = 'audit_events'::regclass
          AND c.relname < 'audit_events_' || to_char(date_trunc('month', keep_from), 'YYYY_MM')
    LOOP
        EXECUTE format('DROP TABLE %I', part);
        dropped := dropped + 1;
    END LOOP;
    RETURN dropped;
END $$;

SELECT audit_events_partition((now() AT TIME ZONE 'UTC')::date);
SELECT audit_events_partition((now() AT TIME ZONE 'UTC' + interval '1 month')::date);

-- Roles are the server's, not the database's: a second database on it finds this one.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'aisolate_audit_writer') THEN
        CREATE ROLE aisolate_audit_writer NOLOGIN;
    END IF;
END $$;
GRANT SELECT, INSERT ON audit_events TO aisolate_audit_writer;
GRANT aisolate_audit_writer TO CURRENT_USER;

-- The typed credentials, sealed by the backend (credentials) and the append-only trail of who stored, deleted
-- and revealed them (credential_events); both are written by the backend alone.
CREATE TABLE credentials (
    secret_ref  text PRIMARY KEY,          -- the Secret's name, as the rows already carry it
    owner_kind  text NOT NULL CHECK (owner_kind IN ('install','space','airlock')),
    owner       text NOT NULL,             -- '' for the install, <space>, <space>/<airlock>
    kind        text NOT NULL CHECK (kind IN ('llm','ds')),
    item_id     text NOT NULL,
    key_id      text NOT NULL,
    nonce       bytea NOT NULL,
    ciphertext  bytea NOT NULL,
    created_by  text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE credential_events (            -- append-only, as audit_events
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    at          timestamptz NOT NULL DEFAULT now(),
    actor       text NOT NULL,
    action      text NOT NULL CHECK (action IN ('stored','deleted','revealed','reveal_refused')),
    secret_ref  text NOT NULL,
    owner_kind  text NOT NULL,
    owner       text NOT NULL,
    kind        text NOT NULL,
    item_id     text NOT NULL,
    reason      text                        -- reveal_refused: unknown | no_auth_time | stale_login | no_ciphertext | key_changed | unreadable
);

CREATE INDEX credential_events_ref ON credential_events (secret_ref, id);

CREATE FUNCTION credential_events_refuse() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'credential_events is append-only: % refused', TG_OP USING ERRCODE = 'insufficient_privilege';
END $$;
CREATE TRIGGER credential_events_append_only BEFORE UPDATE OR DELETE ON credential_events
    FOR EACH ROW EXECUTE FUNCTION credential_events_refuse();
CREATE TRIGGER credential_events_no_truncate BEFORE TRUNCATE ON credential_events
    FOR EACH STATEMENT EXECUTE FUNCTION credential_events_refuse();

GRANT SELECT, INSERT ON credential_events TO aisolate_audit_writer;
