package com.example.shop.order;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderSummary(Long id, String status, BigDecimal total, Instant createdAt) { }
