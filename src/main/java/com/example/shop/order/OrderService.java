package com.example.shop.order;

import com.example.shop.catalog.ProductService;
import com.example.shop.common.job.JobService;
import com.example.shop.user.UserService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

	private final OrderRepository orders;
	private final ProductService products;   // gọi qua service của module catalog
	private final UserService users;
	private final JobService jobs;

	public OrderService(OrderRepository orders, ProductService products, UserService users, JobService jobs) {
		this.orders = orders;
		this.products = products;
		this.users = users;
		this.jobs = jobs;
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

		return OrderDto.from(order);
	}
}
