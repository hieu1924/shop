package com.example.shop.order;

import com.example.shop.common.messaging.MessagingConfig;
import com.example.shop.common.messaging.ProcessedMessageRepository;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@Component
public class OrderCreatedEmailListener {

	private final ProcessedMessageRepository processed;
	private final OrderEmailService emails;
	private final ObjectMapper mapper;

	public OrderCreatedEmailListener(ProcessedMessageRepository processed, OrderEmailService emails,
									 ObjectMapper mapper) {
		this.processed = processed;
		this.emails = emails;
		this.mapper = mapper;
	}

	@RabbitListener(queues = MessagingConfig.EMAIL_QUEUE)
	@Transactional
	public void onOrderCreated(Message message) {
		UUID messageId = UUID.fromString(message.getMessageProperties().getMessageId());
		if (processed.markProcessed(messageId) == 0) {
			return;   // message đến lần hai (at-least-once): đã xử lý rồi, bỏ qua
		}
		OrderCreated event = mapper.readValue(message.getBody(), OrderCreated.class);
		emails.sendConfirmation(event.orderId(), event.userId());
		// Gửi email lỗi -> exception -> rollback, bản ghi processed bị hủy, message được thử lại.
		// Email đã gửi thì không rollback được: hiếm khi (gửi xong nhưng commit lỗi) khách nhận hai email.
	}
}
