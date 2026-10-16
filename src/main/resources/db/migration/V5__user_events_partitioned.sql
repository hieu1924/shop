-- V5__user_events_partitioned.sql (mục 3.2 của bài; bài đặt tên V10, ở đây đánh số liền sau V4)
CREATE TABLE user_events (
    id          BIGSERIAL,
    user_id     BIGINT      NOT NULL,
    event_type  VARCHAR(50) NOT NULL,
    payload     JSONB,
    created_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (id, created_at)          -- khóa chính phải chứa cột partition
) PARTITION BY RANGE (created_at);

-- Partition theo tháng, ranh giới tính theo UTC (ghi rõ +00 để không phụ thuộc múi giờ của session). Các tháng sau do PartitionMaintenance tạo trước (xem class đó).
CREATE TABLE user_events_2026_10 PARTITION OF user_events
    FOR VALUES FROM ('2026-10-01 00:00:00+00') TO ('2026-11-01 00:00:00+00');
CREATE TABLE user_events_2026_11 PARTITION OF user_events
    FOR VALUES FROM ('2026-11-01 00:00:00+00') TO ('2026-12-01 00:00:00+00');
CREATE TABLE user_events_2026_12 PARTITION OF user_events
    FOR VALUES FROM ('2026-12-01 00:00:00+00') TO ('2027-01-01 00:00:00+00');

-- Lưới an toàn: nếu quên tạo partition, dòng mới rơi vào đây thay vì làm INSERT lỗi.
-- Lưu ý: không tạo được partition cho một khoảng thời gian đã có dòng nằm trong default;
-- phải chuyển các dòng đó ra trước.
CREATE TABLE user_events_default PARTITION OF user_events DEFAULT;

CREATE INDEX idx_user_events_user ON user_events (user_id, created_at);
