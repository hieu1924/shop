package com.example.shop.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
