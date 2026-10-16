package com.example.shop.order;

import java.math.BigDecimal;
import java.util.List;

public record OrderDetailDto(Long id, String status, BigDecimal total, List<Line> items) {

	public record Line(Long productId, int quantity, BigDecimal unitPrice) { }

	static OrderDetailDto from(Order o) {
		return new OrderDetailDto(o.getId(), o.getStatus().name(), o.getTotal(),
				o.getItems().stream()
						.map(i -> new Line(i.getProductId(), i.getQuantity(), i.getUnitPrice()))
						.toList());
	}
}
