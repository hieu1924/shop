package com.example.shop.payment;

import java.math.BigDecimal;

public record ChargeRequest(Long orderId, BigDecimal amount) { }
