package com.example.shop.order;

import com.example.shop.catalog.ProductService;
import com.example.shop.common.event.UserEventService;
import com.example.shop.common.job.JobService;
import com.example.shop.user.UserService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class OrderService {

	private final OrderRepository orders;
	private final ProductService products;   // gọi qua service của module catalog
	private final UserService users;
	private final JobService jobs;
	private final UserEventService events;

	public OrderService(OrderRepository orders, ProductService products, UserService users, JobService jobs,
						UserEventService events) {
		this.orders = orders;
		this.products = products;
		this.users = users;
		this.jobs = jobs;
		this.events = events;
	}

	@Transactional
	public OrderDto placeOrder(Long userId, PlaceOrderRequest request) {
		if (!users.exists(userId)) {
			throw new EntityNotFoundException("Không tìm thấy người dùng " + userId);
		}

		Order order = new Order(userId);
		for (PlaceOrderRequest.Line line : request.lines()) {
			order.addItem(products.getEntity(line.productId()), line.quantity());
		}
		orders.save(order);

		// Ghi job gửi email trong CÙNG transaction với đơn hàng:
		// đơn lưu thành công thì job chắc chắn tồn tại, và ngược lại.
		jobs.enqueue(OrderConfirmationEmailHandler.TYPE, new OrderConfirmationPayload(order.getId(), userId));
		events.record(userId, "ORDER_PLACED", new OrderPlacedEvent(order.getId(), order.getTotal()));

		return OrderDto.from(order);
	}

	// Các hàm đọc bên dưới là readOnly nên đi replica (mục 3.1) và có thể chậm hơn primary một chút:
	// đơn vừa đặt có thể chưa hiện ngay. Vì vậy placeOrder trả luôn dữ liệu vừa ghi thay vì bắt client đọc lại.

	// Một đơn kèm items: 1 query JOIN nhờ @EntityGraph. Đơn của người khác trả 404 như không tồn tại.
	@Transactional(readOnly = true)
	public OrderDetailDto getOrder(Long userId, Long orderId) {
		return orders.findWithItemsById(orderId)
				.filter(o -> o.getUserId().equals(userId))
				.map(OrderDetailDto::from)
				.orElseThrow(() -> new EntityNotFoundException("Không tìm thấy đơn hàng " + orderId));
	}

	// Danh sách phân trang: 1 query lấy trang + 1 query IN (...) lấy items của cả trang
	@Transactional(readOnly = true)
	public Page<OrderDetailDto> myOrders(Long userId, Pageable pageable) {
		return orders.findByUserIdOrderByCreatedAtDesc(userId, pageable).map(OrderDetailDto::from);
	}

	// Chỉ vài cột, không đụng tới bảng order_items
	@Transactional(readOnly = true)
	public List<OrderSummary> mySummaries(Long userId, Pageable pageable) {
		return orders.findSummaries(userId, pageable);
	}
}
