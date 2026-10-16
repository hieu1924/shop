-- V4__indexes.sql (mục 2.1 của bài; bài đặt tên V3 nhưng V3 đã dùng cho users)

-- "Đơn hàng của tôi, mới nhất trước": index kép khớp cả WHERE lẫn ORDER BY
CREATE INDEX idx_orders_user_created ON orders (user_id, created_at DESC);

-- foreign key KHÔNG tự có index trong PostgreSQL
CREATE INDEX idx_order_items_order ON order_items (order_id);

CREATE INDEX idx_products_category ON products (category_id);
