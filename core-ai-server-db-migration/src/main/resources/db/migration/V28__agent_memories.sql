CREATE TABLE IF NOT EXISTS app.agent_memories
(
    id         TEXT PRIMARY KEY,
    agent_id   TEXT        NOT NULL,
    type       TEXT,
    layer      TEXT        NOT NULL,
    content    TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ,
    data       JSONB       NOT NULL
);

CREATE INDEX IF NOT EXISTS agent_memories_agent_created_idx
    ON app.agent_memories (agent_id, created_at DESC, id);
CREATE INDEX IF NOT EXISTS agent_memories_agent_layer_created_idx
    ON app.agent_memories (agent_id, layer, created_at);
CREATE INDEX IF NOT EXISTS agent_memories_content_trgm_idx
    ON app.agent_memories USING GIN (content gin_trgm_ops);

CREATE TABLE IF NOT EXISTS app.agent_memory_extraction_cursors
(
    id         TEXT PRIMARY KEY,
    agent_id   TEXT        NOT NULL UNIQUE,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
