-- Additive migration: does not modify legacy watches/events or global ai_usage.
CREATE TABLE IF NOT EXISTS notify_watches_v2 (
 id TEXT PRIMARY KEY, account_id TEXT NOT NULL, url TEXT NOT NULL, title TEXT NOT NULL,
 kind TEXT NOT NULL CHECK(kind IN ('chapter','episode')), selector TEXT NOT NULL,
 latest REAL NOT NULL, latest_url TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 0,
 checked_at INTEGER NOT NULL, next_check INTEGER NOT NULL,
 status TEXT NOT NULL DEFAULT 'ok', failures INTEGER NOT NULL DEFAULT 0,
 UNIQUE(account_id,url), UNIQUE(account_id,id)
);
CREATE INDEX IF NOT EXISTS notify_watches_v2_due ON notify_watches_v2(next_check);
CREATE INDEX IF NOT EXISTS notify_watches_v2_account ON notify_watches_v2(account_id,checked_at);
CREATE TABLE IF NOT EXISTS notify_events_v2 (
 id TEXT PRIMARY KEY, account_id TEXT NOT NULL, watch_id TEXT NOT NULL,
 revision INTEGER NOT NULL, number REAL NOT NULL, url TEXT NOT NULL, created_at INTEGER NOT NULL,
 acknowledged INTEGER NOT NULL DEFAULT 0, UNIQUE(watch_id,revision),
 FOREIGN KEY(account_id,watch_id) REFERENCES notify_watches_v2(account_id,id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS notify_events_v2_account ON notify_events_v2(account_id,acknowledged,created_at);
CREATE TABLE IF NOT EXISTS notify_ai_usage_v2 (
 account_id TEXT NOT NULL, day TEXT NOT NULL, count INTEGER NOT NULL, PRIMARY KEY(account_id,day)
);
