package com.example.shop.order;

import com.example.shop.common.job.JobHandler;
import com.example.shop.user.UserService;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class OrderConfirmationEmailHandler implements JobHandler {

	public static final String TYPE = "ORDER_CONFIRMATION_EMAIL";

	private final JavaMailSender mail;
	private final ObjectMapper mapper;
	private final UserService users;

	public OrderConfirmationEmailHandler(JavaMailSender mail, ObjectMapper mapper, UserService users) {
		this.mail = mail;
		this.mapper = mapper;
		this.users = users;
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public void handle(String payload) throws Exception {
		var p = mapper.readValue(payload, OrderConfirmationPayload.class);
		var msg = new SimpleMailMessage();
		msg.setTo(users.getEmail(p.userId()));
		msg.setSubject("Xác nhận đơn hàng #" + p.orderId());
		msg.setText("Cảm ơn bạn đã đặt hàng!");
		mail.send(msg);
	}
}
