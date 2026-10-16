package com.example.shop.order;

import java.math.BigDecimal;

// Sự kiện "order.created" ghi vào outbox cùng transaction với đơn hàng, các consumer đọc từ RabbitMQ
public record OrderCreated(Long orderId, Long userId, BigDecimal total) { }
