package com.example.shop.order;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

	private final OrderService orderService;

	public OrderController(OrderService orderService) {
		this.orderService = orderService;
	}

	// User lấy từ claim `sub` của JWT (mục 2.4). Với identity provider thật mà `sub` là UUID
	// (Keycloak), cần map sang user id nội bộ, ví dụ bằng claim riêng hoặc tra theo email.
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public OrderDto place(@AuthenticationPrincipal Jwt jwt,
						  @Valid @RequestBody PlaceOrderRequest request) {
		return orderService.placeOrder(userId(jwt), request);
	}

	@GetMapping
	public Page<OrderDetailDto> mine(@AuthenticationPrincipal Jwt jwt,
									 @PageableDefault(size = 20) Pageable pageable) {
		return orderService.myOrders(userId(jwt), pageable);
	}

	@GetMapping("/summaries")
	public List<OrderSummary> summaries(@AuthenticationPrincipal Jwt jwt,
										@PageableDefault(size = 20) Pageable pageable) {
		return orderService.mySummaries(userId(jwt), pageable);
	}

	@GetMapping("/{id}")
	public OrderDetailDto get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
		return orderService.getOrder(userId(jwt), id);
	}

	private static Long userId(Jwt jwt) {
		return Long.valueOf(jwt.getSubject());
	}
}
