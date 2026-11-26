-- A decode rate has to pair answer tokens with the time spent producing them. output_tokens includes
-- reasoning/thinking tokens, and ttft_ms starts at the first token of any kind, so both the numerator
-- and the denominator need a reasoning-aware counterpart.
ALTER TABLE app.spans
    ADD COLUMN IF NOT EXISTS reasoning_tokens BIGINT,
    ADD COLUMN IF NOT EXISTS first_content_ms BIGINT;
