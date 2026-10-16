-- V2__jobs.sql
CREATE TABLE jobs (
    id          BIGSERIAL PRIMARY KEY,
    type        VARCHAR(100) NOT NULL,
    payload     TEXT         NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    attempts    INT          NOT NULL DEFAULT 0,
    run_at      TIMESTAMPTZ  NOT NULL,
    last_error  TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- partial index: chỉ đánh index các job còn chờ, giữ index nhỏ
CREATE INDEX idx_jobs_pending ON jobs (run_at) WHERE status = 'PENDING';
