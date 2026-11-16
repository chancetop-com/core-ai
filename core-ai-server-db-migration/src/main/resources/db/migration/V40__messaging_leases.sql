CREATE TABLE IF NOT EXISTS messaging.pods
(
    pod_id       TEXT PRIMARY KEY,
    address      TEXT        NOT NULL,
    started_at   TIMESTAMPTZ NOT NULL,
    heartbeat_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS pods_heartbeat_idx ON messaging.pods (heartbeat_at);

-- also holds AgentRun leases: the lease key is the session id or the run id
CREATE TABLE IF NOT EXISTS messaging.session_ownership
(
    resource_id TEXT PRIMARY KEY,
    owner_pod   TEXT        NOT NULL,
    acquired_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS session_ownership_owner_idx ON messaging.session_ownership (owner_pod);

CREATE TABLE IF NOT EXISTS messaging.turn_states
(
    session_id TEXT PRIMARY KEY,
    owner_pod  TEXT        NOT NULL,
    user_id    TEXT,
    started_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS turn_states_owner_idx ON messaging.turn_states (owner_pod);
CREATE INDEX IF NOT EXISTS turn_states_user_idx ON messaging.turn_states (user_id);

CREATE TABLE IF NOT EXISTS messaging.user_session_counts
(
    user_id TEXT    NOT NULL,
    pod_id  TEXT    NOT NULL,
    count   INTEGER NOT NULL,
    PRIMARY KEY (user_id, pod_id)
);
