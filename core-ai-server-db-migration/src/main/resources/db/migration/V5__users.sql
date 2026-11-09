CREATE TABLE IF NOT EXISTS app.users (
    id text PRIMARY KEY,
    name text,
    email text,
    api_key text,
    role text,
    status text,
    user_type text,
    owner_id text,
    external_id text,
    created_at timestamptz,
    last_login_at timestamptz,
    data jsonb NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS users_email_idx ON app.users (email) WHERE email IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS users_api_key_idx ON app.users (api_key) WHERE api_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS users_user_type_created_idx ON app.users (user_type, created_at);
CREATE INDEX IF NOT EXISTS users_owner_external_idx ON app.users (owner_id, external_id);
