package com.example.shop.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record PlaceOrderRequest(
		@NotEmpty List<@Valid Line> lines) {

	public record Line(@NotNull Long productId, @Min(1) int quantity) { }
}
