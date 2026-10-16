package com.example.shop.catalog;

import com.example.shop.common.config.CacheConfig;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class ProductService {

	private final ProductRepository products;

	public ProductService(ProductRepository products) {
		this.products = products;
	}

	// Cache DTO, KHÔNG cache entity JPA (entity chứa proxy lazy-loading, serialize lỗi).
	// sync = true: khi cache miss, chỉ một luồng trong instance truy vấn DB, các luồng khác chờ
	// kết quả -> giảm cache stampede (chỉ trong phạm vi một instance).
	@Cacheable(cacheNames = CacheConfig.PRODUCTS, key = "#id", sync = true)
	@Transactional(readOnly = true)
	public ProductDto getProduct(Long id) {
		return ProductDto.from(getEntity(id));
	}

	@Transactional(readOnly = true)
	public Page<ProductDto> list(Pageable pageable) {
		return products.findAll(pageable).map(ProductDto::from);
	}

	// Xóa cache khi giá đổi; cache là transactionAware nên việc xóa chỉ chạy SAU khi commit
	@CacheEvict(cacheNames = CacheConfig.PRODUCTS, key = "#id")
	@Transactional
	public ProductDto updatePrice(Long id, BigDecimal newPrice) {
		Product p = getEntity(id);
		p.changePrice(newPrice);
		return ProductDto.from(p);
	}

	// Dành cho module order; chạy trong transaction của nơi gọi
	public Product getEntity(Long id) {
		return products.findById(id)
				.orElseThrow(() -> new EntityNotFoundException("Không tìm thấy sản phẩm " + id));
	}
}
