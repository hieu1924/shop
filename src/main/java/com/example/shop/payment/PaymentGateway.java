package com.example.shop.payment;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Service
public class PaymentGateway {

	private final RestClient client;

	public PaymentGateway(RestClient paymentClient) {
		this.client = paymentClient;
	}

	// Mặc định Retry bọc ngoài CircuitBreaker. Fallback đặt ở Retry (lớp ngoài): nếu đặt ở CircuitBreaker,
	// lỗi đầu tiên đã trả fallback và Retry không bao giờ chạy.
	@CircuitBreaker(name = "payment")
	@Retry(name = "payment", fallbackMethod = "paymentUnavailable")
	public PaymentResult charge(ChargeRequest request) {
		return client.post()
				.uri("/charges")
				// Bắt buộc khi có retry: nhà cung cấp dùng key này để không trừ tiền hai lần
				.header("Idempotency-Key", "order-" + request.orderId())
				.body(request)
				.retrieve()
				.body(PaymentResult.class);
	}

	// Cùng tham số + Throwable. Chạy khi hết lượt retry hoặc mạch đang mở (CallNotPermittedException):
	// không gọi nữa, đánh dấu chờ xử lý sau.
	PaymentResult paymentUnavailable(ChargeRequest request, Throwable cause) {
		if (cause instanceof HttpClientErrorException e) {
			throw e;   // 4xx là request sai, không phải sự cố tạm thời: đừng giả vờ là "chờ xử lý"
		}
		return PaymentResult.pending(request.orderId());
	}
}
