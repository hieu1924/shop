package com.example.shop.order;

import com.example.shop.catalog.Product;
import jakarta.persistence.*;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@Table(name = "orders")
public class Order {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private OrderStatus status = OrderStatus.PENDING;

	@Column(nullable = false)
	private BigDecimal total = BigDecimal.ZERO;

	@Column(nullable = false)
	private Instant createdAt = Instant.now();

	@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<OrderItem> items = new ArrayList<>();

	protected Order() { }

	public Order(Long userId) {
		this.userId = userId;
	}

	public void addItem(Product product, int quantity) {
		items.add(new OrderItem(this, product.getId(), quantity, product.getPrice()));
		total = total.add(product.getPrice().multiply(BigDecimal.valueOf(quantity)));
	}
}
