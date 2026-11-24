-- Retained-pressure signal and session footprint high-water marks (Design C).
-- The old sample rows keep NULL in these columns; the dashboard treats NULL as "not reported by that build".
ALTER TABLE app.runtime_samples
    ADD COLUMN IF NOT EXISTS old_gen_percent DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS gc_kind TEXT,
    ADD COLUMN IF NOT EXISTS gc_reclaimed_bytes BIGINT,
    ADD COLUMN IF NOT EXISTS sessions_estimated_bytes_peak_total BIGINT,
    ADD COLUMN IF NOT EXISTS sessions_estimated_bytes_peak_max BIGINT,
    ADD COLUMN IF NOT EXISTS sessions_inline_image_bytes_peak_total BIGINT,
    ADD COLUMN IF NOT EXISTS sessions_media_ref_bytes_total BIGINT;
