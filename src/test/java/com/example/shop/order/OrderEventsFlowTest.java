package com.example.shop.order;

import com.example.shop.catalog.Product;
import com.example.shop.catalog.ProductRepository;
import com.example.shop.common.messaging.MessagingConfig;
import com.example.shop.user.User;
import com.example.shop.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// Chạy toàn bộ đường đi: orders + outbox (một transaction) -> OutboxRelay -> RabbitMQ -> listener -> email.
// Cần PostgreSQL và RabbitMQ đang chạy (docker compose up -d) và KHÔNG có bản app khác (bootRun) đang
// consume cùng queue, nếu không message có thể bị bản đó lấy mất.
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=true")
class OrderEventsFlowTest {

	@MockitoBean JavaMailSender mail;

	@Autowired OrderService orders;
	@Autowired UserRepository users;
	@Autowired ProductRepository products;
	@Autowired JdbcTemplate jdbc;
	@Autowired RabbitTemplate rabbit;
	@Autowired AmqpAdmin admin;
	@Autowired io.micrometer.core.instrument.MeterRegistry registry;

	Long userId;
	Long productId;
	String email;

	@BeforeEach
	void setUp() {
		admin.purgeQueue(MessagingConfig.EMAIL_QUEUE, false);
		admin.purgeQueue(MessagingConfig.EMAIL_DLQ, false);
		reset(mail);
		email = UUID.randomUUID() + "@test.local";
		userId = users.save(new User(email, "Flow Tester")).getId();
		productId = products.save(new Product("Flow product", new BigDecimal("5.00"), 1L)).getId();
	}

	@AfterEach
	void cleanUp() {
		List<UUID> eventIds = jdbc.queryForList(
				"select id from outbox_events where (payload::jsonb->>'userId')::bigint = ?", UUID.class, userId);
		eventIds.forEach(id -> jdbc.update("delete from processed_messages where id = ?", id));
		jdbc.update("delete from outbox_events where (payload::jsonb->>'userId')::bigint = ?", userId);
		jdbc.update("delete from order_items where order_id in (select id from orders where user_id = ?)", userId);
		jdbc.update("delete from orders where user_id = ?", userId);
		jdbc.update("delete from user_events where user_id = ?", userId);
		jdbc.update("delete from users where id = ?", userId);
		jdbc.update("delete from products where id = ?", productId);
	}

	private Long placeOrder() {
		return orders.placeOrder(userId, new PlaceOrderRequest(
				List.of(new PlaceOrderRequest.Line(productId, 2)))).id();
	}

	@Test
	void orderCreatesOutboxEventWhichEndsUpAsOneEmail() {
		double countBefore = registry.counter("shop.orders.placed").count();
		Long orderId = placeOrder();
		assertThat(registry.counter("shop.orders.placed").count()).isEqualTo(countBefore + 1);   // commit thật -> đếm 1

		// outbox ghi cùng transaction với đơn hàng
		assertThat(jdbc.queryForObject("select count(*) from outbox_events where routing_key = 'order.created'"
				+ " and (payload::jsonb->>'orderId')::bigint = ?", Integer.class, orderId)).isEqualTo(1);

		var captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
		verify(mail, timeout(15_000)).send(captor.capture());
		assertThat(captor.getValue().getTo()).containsExactly(email);
		assertThat(captor.getValue().getSubject()).contains("#" + orderId);

		await().untilAsserted(() -> assertThat(jdbc.queryForObject(
				"select count(*) from outbox_events where published_at is not null"
						+ " and (payload::jsonb->>'orderId')::bigint = ?", Integer.class, orderId)).isEqualTo(1));
	}

	@Test
	void sameMessageDeliveredTwiceSendsOnlyOneEmail() {
		Long orderId = placeOrder();
		verify(mail, timeout(15_000)).send(any(SimpleMailMessage.class));

		// Gửi lại đúng message id đó (mô phỏng broker/relay gửi trùng)
		UUID messageId = jdbc.queryForObject(
				"select id from outbox_events where (payload::jsonb->>'orderId')::bigint = ?", UUID.class, orderId);
		Message duplicate = MessageBuilder
				.withBody(("{\"orderId\":" + orderId + ",\"userId\":" + userId + ",\"total\":10.00}").getBytes())
				.setMessageId(messageId.toString()).build();
		rabbit.send(MessagingConfig.EVENTS_EXCHANGE, "order.created", duplicate);

		verify(mail, after(2_000).times(1)).send(any(SimpleMailMessage.class));
	}

	@Test
	void failingEmailIsRetriedThenDeadLettered() {
		doThrow(new RuntimeException("SMTP down")).when(mail).send(any(SimpleMailMessage.class));
		Long orderId = placeOrder();

		// 1 lần đầu + 3 lần retry (1s, 2s, 4s) rồi sang DLQ
		Message dead = rabbit.receive(MessagingConfig.EMAIL_DLQ, 30_000);

		assertThat(dead).isNotNull();
		assertThat(new String(dead.getBody())).contains("\"orderId\":" + orderId);
		verify(mail, times(4)).send(any(SimpleMailMessage.class));
		// rollback cùng transaction: message chưa được đánh dấu đã xử lý nên sửa lỗi rồi đẩy lại được
		assertThat(jdbc.queryForObject("select count(*) from processed_messages where id = ?::uuid", Integer.class,
				dead.getMessageProperties().getMessageId())).isZero();
	}
}
