-- V6__outbox.sql (mục 3.4 của bài; bài đặt tên V11, ở đây đánh số liền sau V5)
CREATE TABLE outbox_events (
    id            UUID PRIMARY KEY,
    routing_key   VARCHAR(100) NOT NULL,
    payload       TEXT         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ
);
-- partial index: chỉ các sự kiện chưa gửi, giữ index nhỏ
CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;

-- Consumer ghi lại id message đã xử lý để chống xử lý trùng
CREATE TABLE processed_messages (
    id            UUID PRIMARY KEY,
    processed_at  TIMESTAMPTZ NOT NULL
);
