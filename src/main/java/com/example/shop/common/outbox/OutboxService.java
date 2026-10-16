package com.example.shop.common.outbox;

import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class OutboxService {

	private final OutboxRepository outbox;
	private final ObjectMapper mapper;

	public OutboxService(OutboxRepository outbox, ObjectMapper mapper) {
		this.outbox = outbox;
		this.mapper = mapper;
	}

	// Không có @Transactional riêng: tham gia transaction của nơi gọi (giống JobService.enqueue),
	// nên sự kiện tồn tại khi và chỉ khi dữ liệu nghiệp vụ được commit.
	public void publish(String routingKey, Object event) {
		outbox.save(OutboxEvent.of(routingKey, mapper.writeValueAsString(event)));
	}
}
