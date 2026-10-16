package com.example.shop.catalog;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record UpdatePriceRequest(@NotNull @DecimalMin("0.00") BigDecimal price) { }
