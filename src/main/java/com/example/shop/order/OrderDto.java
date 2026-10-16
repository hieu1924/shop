package com.example.shop.order;

import java.math.BigDecimal;

public record OrderDto(Long id, String status, BigDecimal total) {

	static OrderDto from(Order o) {
		return new OrderDto(o.getId(), o.getStatus().name(), o.getTotal());
	}
}
