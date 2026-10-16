package com.example.shop.catalog;

import java.io.Serializable;
import java.math.BigDecimal;

// Serializable vì được lưu vào Redis bằng cơ chế mặc định của Java. Khi đổi cấu trúc record này,
// đổi luôn tên cache (ví dụ products-v2) để không đọc nhầm dữ liệu kiểu cũ.
public record ProductDto(Long id, String name, BigDecimal price, Long categoryId) implements Serializable {

	static ProductDto from(Product p) {
		return new ProductDto(p.getId(), p.getName(), p.getPrice(), p.getCategoryId());
	}
}
