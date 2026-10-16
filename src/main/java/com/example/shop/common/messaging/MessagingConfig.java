package com.example.shop.common.messaging;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MessagingConfig {

	public static final String EVENTS_EXCHANGE = "shop.events";
	public static final String DLX = "shop.dlx";
	public static final String EMAIL_QUEUE = "email.order-created";
	public static final String EMAIL_DLQ = EMAIL_QUEUE + ".dlq";

	@Bean
	TopicExchange eventsExchange() {
		return new TopicExchange(EVENTS_EXCHANGE);
	}

	@Bean
	DirectExchange deadLetterExchange() {
		return new DirectExchange(DLX);
	}

	// Message bị consumer từ chối (hết lượt retry) sẽ được RabbitMQ chuyển sang DLX -> DLQ
	@Bean
	Queue emailQueue() {
		return QueueBuilder.durable(EMAIL_QUEUE)
				.deadLetterExchange(DLX)
				.deadLetterRoutingKey(EMAIL_DLQ)
				.build();
	}

	@Bean
	Queue emailDeadLetterQueue() {
		return QueueBuilder.durable(EMAIL_DLQ).build();
	}

	@Bean
	Binding emailBinding() {
		return BindingBuilder.bind(emailQueue()).to(eventsExchange()).with("order.created");
	}

	@Bean
	Binding emailDlqBinding() {
		return BindingBuilder.bind(emailDeadLetterQueue()).to(deadLetterExchange()).with(EMAIL_DLQ);
	}
}
