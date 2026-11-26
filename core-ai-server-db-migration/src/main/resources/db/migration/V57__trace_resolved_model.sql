-- The model that actually answered the LLM call when it differs from the requested alias
-- (gateway failover or an alias mapped to a renamed upstream). The gateway records it on the
-- LLM span; ingest rolls the first one up onto the trace so lists and pricing see the real model.

ALTER TABLE app.traces ADD COLUMN IF NOT EXISTS resolved_model TEXT;
ALTER TABLE app.spans ADD COLUMN IF NOT EXISTS resolved_model TEXT;
