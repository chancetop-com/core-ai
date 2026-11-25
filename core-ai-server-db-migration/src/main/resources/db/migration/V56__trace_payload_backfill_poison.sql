ALTER TABLE app.trace_payloads
    ADD COLUMN IF NOT EXISTS backfill_error TEXT,
    ADD COLUMN IF NOT EXISTS backfill_attempted_at TIMESTAMPTZ;