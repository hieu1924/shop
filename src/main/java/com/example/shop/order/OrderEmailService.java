package com.example.shop.order;

import com.example.shop.user.UserService;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class OrderEmailService {

	private final JavaMailSender mail;
	private final UserService users;

	public OrderEmailService(JavaMailSender mail, UserService users) {
		this.mail = mail;
		this.users = users;
	}

	public void sendConfirmation(Long orderId, Long userId) {
		var msg = new SimpleMailMessage();
		msg.setTo(users.getEmail(userId));
		msg.setSubject("Xác nhận đơn hàng #" + orderId);
		msg.setText("Cảm ơn bạn đã đặt hàng!");
		mail.send(msg);
	}
}
