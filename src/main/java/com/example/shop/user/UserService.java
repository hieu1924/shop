package com.example.shop.user;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

	private final UserRepository users;

	public UserService(UserRepository users) {
		this.users = users;
	}

	@Transactional(readOnly = true)
	public String getEmail(Long userId) {
		return users.findById(userId)
				.map(User::getEmail)
				.orElseThrow(() -> new EntityNotFoundException("Không tìm thấy người dùng " + userId));
	}

	@Transactional(readOnly = true)
	public boolean exists(Long userId) {
		return users.existsById(userId);
	}
}
