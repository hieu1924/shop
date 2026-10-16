package com.example.shop.order;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

	private final OrderService orderService;

	public OrderController(OrderService orderService) {
		this.orderService = orderService;
	}

	// Giai đoạn 1 chưa có xác thực: tạm lấy user từ header X-User-Id.
	// Mục 2.4 của bài thay bằng JWT (@AuthenticationPrincipal Jwt).
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public OrderDto place(@RequestHeader("X-User-Id") Long userId,
						  @Valid @RequestBody PlaceOrderRequest request) {
		return orderService.placeOrder(userId, request);
	}
}
