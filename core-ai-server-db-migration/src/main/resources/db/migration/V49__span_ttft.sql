-- Time from issuing an LLM request to the first content token, recorded by the provider template and
-- carried on the LLM span. NULL for calls made before the measurement existed and for replies without
-- text (tool-only, cancelled before output). The dashboard reads it per model for TTFT averages/percentiles.
ALTER TABLE app.spans ADD COLUMN IF NOT EXISTS ttft_ms BIGINT;
