CREATE TABLE applications (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    company TEXT NOT NULL,
    company_key TEXT NOT NULL,
    role TEXT,
    stage TEXT NOT NULL,
    interview_rounds INTEGER NOT NULL DEFAULT 0,
    applied_on TEXT,
    notes TEXT,
    created_at TEXT,
    updated_at TEXT
);

CREATE TABLE field_locks (
    application_id INTEGER,
    field TEXT,
    PRIMARY KEY (application_id, field)
);

-- status: OPEN | DONE | CLOSED
CREATE TABLE actions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    application_id INTEGER NOT NULL,
    description TEXT NOT NULL,
    due_at TEXT,
    status TEXT NOT NULL,
    stage TEXT NOT NULL,
    source_email_id INTEGER,
    created_at TEXT
);

-- status: PENDING | FILTERED | NOT_PLACEMENT | PROCESSED | REVIEW | FAILED | DUPLICATE
CREATE TABLE emails (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account TEXT,
    message_id TEXT,
    thread_id TEXT,
    dedupe_key TEXT,
    from_address TEXT,
    subject TEXT,
    received_at TEXT,
    web_link TEXT,
    application_id INTEGER,
    status TEXT,
    attempts INTEGER DEFAULT 0,
    last_error TEXT,
    UNIQUE (account, message_id)
);
CREATE INDEX idx_emails_dedupe_key ON emails (dedupe_key);

-- source: EMAIL | USER
CREATE TABLE stage_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    application_id INTEGER,
    from_stage TEXT,
    to_stage TEXT,
    changed_at TEXT,
    source TEXT,
    email_id INTEGER
);

CREATE TABLE review_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    email_id INTEGER,
    application_id INTEGER,
    reason TEXT,
    suggestion_json TEXT,
    status TEXT,
    created_at TEXT
);

CREATE TABLE sync_state (
    account TEXT PRIMARY KEY,
    high_water TEXT,
    last_success_at TEXT,
    status TEXT,
    last_error TEXT
);

CREATE TABLE notifications_sent (
    key TEXT PRIMARY KEY,
    sent_at TEXT
);
