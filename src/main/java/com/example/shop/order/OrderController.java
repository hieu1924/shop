package com.example.shop.order;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

	@GetMapping
	public Page<OrderDetailDto> mine(@RequestHeader("X-User-Id") Long userId,
									 @PageableDefault(size = 20) Pageable pageable) {
		return orderService.myOrders(userId, pageable);
	}

	@GetMapping("/summaries")
	public List<OrderSummary> summaries(@RequestHeader("X-User-Id") Long userId,
										@PageableDefault(size = 20) Pageable pageable) {
		return orderService.mySummaries(userId, pageable);
	}

	@GetMapping("/{id}")
	public OrderDetailDto get(@RequestHeader("X-User-Id") Long userId, @PathVariable Long id) {
		return orderService.getOrder(userId, id);
	}
}
