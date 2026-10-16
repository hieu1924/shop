package com.example.shop.payment;

// status: do nhà cung cấp trả (vd. "SUCCEEDED") hoặc "PENDING" khi cổng thanh toán tạm thời không dùng được
public record PaymentResult(Long orderId, String status) {

	public static final String PENDING = "PENDING";

	public static PaymentResult pending(Long orderId) {
		return new PaymentResult(orderId, PENDING);
	}

	public boolean isPending() {
		return PENDING.equals(status);
	}
}
