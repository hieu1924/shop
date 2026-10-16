package com.example.shop.order;

import com.example.shop.catalog.Product;
import com.example.shop.catalog.ProductRepository;
import com.example.shop.support.SqlCounter;
import com.example.shop.user.User;
import com.example.shop.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Chạy trong một transaction rồi rollback: không để lại dữ liệu trong DB dev
@SpringBootTest(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.example.shop.support.SqlCounter")
@Transactional
class OrderQueryTest {

	@Autowired OrderService orders;
	@Autowired UserRepository users;
	@Autowired ProductRepository products;
	@Autowired EntityManager em;
	@Autowired io.micrometer.core.instrument.MeterRegistry registry;

	@Test
	void listingOrdersDoesNotIssueOneQueryPerOrder() {
		User user = users.save(new User(UUID.randomUUID() + "@test.local", "Tester"));
		Product p = products.save(new Product("Áo", new BigDecimal("10.00"), 1L));
		for (int i = 0; i < 5; i++) {
			orders.placeOrder(user.getId(), new PlaceOrderRequest(
					List.of(new PlaceOrderRequest.Line(p.getId(), 2), new PlaceOrderRequest.Line(p.getId(), 1))));
		}
		em.flush();
		em.clear();   // bỏ cache cấp 1 để đọc lại từ DB
		SqlCounter.reset();

		var page = orders.myOrders(user.getId(), PageRequest.of(0, 20));

		assertThat(page.getContent()).hasSize(5);
		assertThat(page.getContent()).allSatisfy(o -> assertThat(o.items()).hasSize(2));
		// 1 query lấy trang + 1 query IN (...) lấy items của cả 5 đơn; không phải 1 + 5
		assertThat(SqlCounter.statements()).hasSize(2);
	}

	@Test
	void singleOrderLoadsItemsWithOneJoin() {
		User user = users.save(new User(UUID.randomUUID() + "@test.local", "Tester"));
		Product p = products.save(new Product("Áo", new BigDecimal("10.00"), 1L));
		Long id = orders.placeOrder(user.getId(), new PlaceOrderRequest(
				List.of(new PlaceOrderRequest.Line(p.getId(), 2)))).id();
		em.flush();
		em.clear();
		SqlCounter.reset();

		var detail = orders.getOrder(user.getId(), id);

		assertThat(detail.items()).hasSize(1);
		assertThat(SqlCounter.statements()).hasSize(1);
	}

	@Test
	void orderCounterOnlyCountsAfterCommit() {
		User user = users.save(new User(UUID.randomUUID() + "@test.local", "Tester"));
		Product p = products.save(new Product("Áo", new BigDecimal("10.00"), 1L));
		double before = registry.counter("shop.orders.placed").count();

		orders.placeOrder(user.getId(), new PlaceOrderRequest(List.of(new PlaceOrderRequest.Line(p.getId(), 1))));

		// Test này rollback nên afterCommit không bao giờ chạy: nếu đếm ngay trong transaction thì số đã tăng
		assertThat(registry.counter("shop.orders.placed").count()).isEqualTo(before);
	}
}
