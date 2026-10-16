package com.example.shop.order;

import java.math.BigDecimal;

// Nội dung ghi vào cột payload (JSONB) của bảng user_events
record OrderPlacedEvent(Long orderId, BigDecimal total) { }
