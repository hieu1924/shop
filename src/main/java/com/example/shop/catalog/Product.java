package com.example.shop.catalog;

import jakarta.persistence.*;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Getter
@Table(name = "products")
public class Product {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private BigDecimal price;

	@Column(nullable = false)
	private Long categoryId;

	@Column(nullable = false)
	private Instant updatedAt = Instant.now();

	protected Product() { }

	public Product(String name, BigDecimal price, Long categoryId) {
		this.name = name;
		this.price = price;
		this.categoryId = categoryId;
	}

	public void changePrice(BigDecimal newPrice) {
		this.price = newPrice;
		this.updatedAt = Instant.now();
	}
}
