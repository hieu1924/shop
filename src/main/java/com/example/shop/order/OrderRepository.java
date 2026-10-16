package com.example.shop.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

	// Một query JOIN lấy luôn items
	@EntityGraph(attributePaths = "items")
	Optional<Order> findWithItemsById(Long id);

	// Danh sách có phân trang: KHÔNG fetch join collection (Hibernate sẽ
	// phân trang trong RAM). Lấy trang đơn hàng trước, items được gom
	// bằng default_batch_fetch_size (application.yml).
	Page<Order> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

	// Màn hình chỉ cần vài cột: projection thay vì nạp cả entity
	@Query("""
			select new com.example.shop.order.OrderSummary(o.id, cast(o.status as string), o.total, o.createdAt)
			from Order o where o.userId = :userId order by o.createdAt desc
			""")
	List<OrderSummary> findSummaries(@Param("userId") Long userId, Pageable pageable);
}
