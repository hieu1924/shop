package com.example.shop.common.outbox;

import com.example.shop.common.messaging.MessagingConfig;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

// Đọc bảng outbox rồi đẩy lên RabbitMQ. SKIP LOCKED nên nhiều instance cùng chạy không gửi trùng lô.
@Component
public class OutboxRelay {

	private static final long CONFIRM_TIMEOUT_MS = 5000;

	private final OutboxRepository outbox;
	private final RabbitTemplate rabbit;

	public OutboxRelay(OutboxRepository outbox, RabbitTemplate rabbit) {
		this.outbox = outbox;
		this.rabbit = rabbit;
	}

	@Scheduled(fixedDelay = 500)
	@Transactional
	public void publish() {
		var batch = outbox.lockUnpublished(100);
		if (batch.isEmpty()) {
			return;
		}
		// invoke(): gửi cả lô trên cùng một channel rồi chờ broker xác nhận (publisher confirms).
		// Broker nack hoặc quá hạn -> exception -> rollback -> sự kiện vẫn chưa published, lần sau gửi lại.
		rabbit.invoke(t -> {
			for (OutboxEvent e : batch) {
				MessageProperties props = new MessageProperties();
				props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
				props.setMessageId(e.getId().toString());
				props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);

				t.send(MessagingConfig.EVENTS_EXCHANGE, e.getRoutingKey(),
						new Message(e.getPayload().getBytes(StandardCharsets.UTF_8), props));
			}
			t.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
			return null;
		});
		batch.forEach(OutboxEvent::markPublished);
	}
}
