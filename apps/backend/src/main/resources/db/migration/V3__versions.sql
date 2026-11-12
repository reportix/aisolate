-- Airlock versions: the airlock is the container (who may ask, its LLMs and data sources), a version is
-- what runs, on its own endpoint. What runs leaves the airlock row for a version row; the code moves with
-- it. An airlock that was deployed, or at least named its model, becomes its version v1; a never-deployed
-- airlock gets none. A version holds no credential: its LLMs take no key, its data sources are its
-- airlock's, so credentials.owner_kind stays install, space or airlock.

-- What runs: a version named by its developer, its form as flat JSON saved in place (revision counts
-- the saves) and what the code its deploy brought holds: the jar's (a Python project's zip, jar_language
-- python) digest and size, and the tools, handlers and rule sets read from it. The bytes are in
-- airlock_version_code. The airlock's row takes its versions with it.
CREATE TABLE airlock_versions (
    airlock_id   text        NOT NULL REFERENCES airlocks (id) ON DELETE CASCADE,
    definition   jsonb       NOT NULL,
    revision     int         NOT NULL DEFAULT 1,
    created_by   text,
    created_by_name text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    version      text        NOT NULL CHECK (version ~ '^[a-z0-9]([a-z0-9-]{0,29}[a-z0-9])?$'),
    description  text        NOT NULL DEFAULT '',
    jar_digest   text,
    jar_size     bigint,
    jar_tools    jsonb,
    jar_handlers jsonb,
    jar_rules    jsonb,
    jar_language text        CHECK (jar_language IN ('java', 'python')),
    PRIMARY KEY (airlock_id, version)
);

-- The full name is the AirlockVersion's resource name, so one across the install: both slugs allow
-- dashes, and hr-assistant/eu-v1 is the same resource as hr-assistant-eu/v1.
CREATE UNIQUE INDEX airlock_versions_full_name ON airlock_versions ((airlock_id || '-' || version));

-- Each airlock that runs something becomes its v1: what ran, with the code and whoever deployed it.
INSERT INTO airlock_versions (airlock_id, version, description, definition, created_by, created_by_name,
                              created_at, updated_at, jar_digest, jar_size, jar_tools, jar_handlers,
                              jar_rules, jar_language)
SELECT id, 'v1', '',
       definition - 'roles' - 'callers' - 'clients' - 'llms' - 'data_sources',
       created_by, created_by_name, created_at, updated_at,
       jar_digest, jar_size, jar_tools, jar_handlers, jar_rules, jar_language
FROM airlocks
WHERE jar_digest IS NOT NULL OR COALESCE(definition->>'llm', '') <> '';

UPDATE airlocks
SET definition = definition - 'context_preparators' - 'memory_handlers' - 'llm' - 'prompt' - 'tools'
                            - 'verifiers';

-- The code bytes follow what runs: every chunk was a v1's.
ALTER TABLE airlock_code RENAME TO airlock_version_code;
ALTER TABLE airlock_version_code ADD COLUMN version text NOT NULL DEFAULT 'v1';
ALTER TABLE airlock_version_code ALTER COLUMN version DROP DEFAULT;
ALTER TABLE airlock_version_code DROP CONSTRAINT airlock_code_pkey;
ALTER TABLE airlock_version_code DROP CONSTRAINT airlock_code_airlock_id_fkey;
ALTER TABLE airlock_version_code ADD PRIMARY KEY (airlock_id, version, seq);
ALTER TABLE airlock_version_code
    ADD FOREIGN KEY (airlock_id, version) REFERENCES airlock_versions (airlock_id, version) ON DELETE CASCADE;

ALTER TABLE airlocks
    DROP COLUMN jar_digest, DROP COLUMN jar_size, DROP COLUMN jar_tools, DROP COLUMN jar_handlers,
    DROP COLUMN jar_rules, DROP COLUMN jar_language;

-- Which version of the airlock handled the exchange. Lines before versions have none; the ingest refuses
-- a new line without it. The chain stays per airlock: audit_events_chain and verify are unchanged.
ALTER TABLE audit_events ADD COLUMN version text;
