CREATE TABLE IF NOT EXISTS watches (
 id TEXT PRIMARY KEY, url TEXT NOT NULL UNIQUE, title TEXT NOT NULL,
 kind TEXT NOT NULL CHECK(kind IN ('chapter','episode')), selector TEXT NOT NULL,
 latest REAL NOT NULL, latest_url TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 0,
 checked_at INTEGER NOT NULL, next_check INTEGER NOT NULL,
 status TEXT NOT NULL DEFAULT 'ok', failures INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS watches_due ON watches(next_check);
CREATE TABLE IF NOT EXISTS ai_usage (day TEXT PRIMARY KEY, count INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS events (
 id TEXT PRIMARY KEY, watch_id TEXT NOT NULL REFERENCES watches(id) ON DELETE CASCADE,
 revision INTEGER NOT NULL, number REAL NOT NULL, url TEXT NOT NULL, created_at INTEGER NOT NULL,
 acknowledged INTEGER NOT NULL DEFAULT 0, UNIQUE(watch_id,revision)
);
CREATE INDEX IF NOT EXISTS events_pending ON events(acknowledged,created_at);
