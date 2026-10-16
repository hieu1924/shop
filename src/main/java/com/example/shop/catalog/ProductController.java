package com.example.shop.catalog;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products")
public class ProductController {

	private final ProductService productService;

	public ProductController(ProductService productService) {
		this.productService = productService;
	}

	@GetMapping
	public Page<ProductDto> list(@PageableDefault(size = 20) Pageable pageable) {
		return productService.list(pageable);
	}

	@GetMapping("/{id}")
	public ProductDto get(@PathVariable Long id) {
		return productService.getProduct(id);
	}

	// Cần scope catalog:write (xem SecurityConfig)
	@PutMapping("/{id}/price")
	public ProductDto updatePrice(@PathVariable Long id, @Valid @RequestBody UpdatePriceRequest request) {
		return productService.updatePrice(id, request.price());
	}
}
