package com.example.shop.catalog;

import java.math.BigDecimal;

public record ProductDto(Long id, String name, BigDecimal price, Long categoryId) {

	static ProductDto from(Product p) {
		return new ProductDto(p.getId(), p.getName(), p.getPrice(), p.getCategoryId());
	}
}
