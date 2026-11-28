-- V0.3.3. Session metadata only. Times are Unix seconds.
CREATE TABLE IF NOT EXISTS summit_sessions (
  sid TEXT PRIMARY KEY,
  credential_hash TEXT NOT NULL UNIQUE,
  google_sub TEXT NOT NULL,
  expires INTEGER NOT NULL,
  revoked INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS summit_sessions_expiry ON summit_sessions(expires);
