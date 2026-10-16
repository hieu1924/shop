package com.example.shop.order;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
class OrderMetrics {

	private final Counter ordersPlaced;

	OrderMetrics(MeterRegistry registry) {
		this.ordersPlaced = Counter.builder("shop.orders.placed")
				.description("Số đơn hàng được tạo")
				.register(registry);
	}

	// Chỉ đếm sau khi transaction commit: đơn bị rollback không được tính
	void orderPlaced() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					ordersPlaced.increment();
				}
			});
		} else {
			ordersPlaced.increment();
		}
	}
}
