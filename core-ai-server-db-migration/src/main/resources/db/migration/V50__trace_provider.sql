-- The provider that actually served an LLM call is known only when the gateway routes it.
-- Persisting it on the trace/span keeps analytics and pricing attribution stable when the same
-- model alias is registered on several providers (priority/failover routes).
ALTER TABLE app.traces ADD COLUMN IF NOT EXISTS provider_id TEXT;
ALTER TABLE app.spans ADD COLUMN IF NOT EXISTS provider_id TEXT;

CREATE INDEX IF NOT EXISTS traces_provider_started_idx ON app.traces (provider_id, started_at DESC);
CREATE INDEX IF NOT EXISTS spans_provider_started_idx ON app.spans (provider_id, started_at DESC);
