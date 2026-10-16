package com.example.shop.catalog;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

	private final ProductRepository products;

	public ProductService(ProductRepository products) {
		this.products = products;
	}

	@Transactional(readOnly = true)
	public ProductDto getProduct(Long id) {
		return ProductDto.from(getEntity(id));
	}

	@Transactional(readOnly = true)
	public Page<ProductDto> list(Pageable pageable) {
		return products.findAll(pageable).map(ProductDto::from);
	}

	// Dành cho module order; chạy trong transaction của nơi gọi
	public Product getEntity(Long id) {
		return products.findById(id)
				.orElseThrow(() -> new EntityNotFoundException("Không tìm thấy sản phẩm " + id));
	}
}
