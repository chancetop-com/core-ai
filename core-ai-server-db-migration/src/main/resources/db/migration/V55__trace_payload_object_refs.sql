ALTER TABLE app.trace_payloads
    ALTER COLUMN content DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS object_hash TEXT,
    ADD COLUMN IF NOT EXISTS storage_key TEXT,
    ADD COLUMN IF NOT EXISTS content_type TEXT,
    ADD COLUMN IF NOT EXISTS size_bytes BIGINT;

CREATE INDEX IF NOT EXISTS trace_payloads_object_hash_idx
    ON app.trace_payloads (object_hash) WHERE object_hash IS NOT NULL;

CREATE INDEX IF NOT EXISTS trace_payloads_storage_key_idx
    ON app.trace_payloads (storage_key) WHERE storage_key IS NOT NULL;
