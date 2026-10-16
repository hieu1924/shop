package com.example.shop.common.event;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

// Bảng user_events chỉ thêm dữ liệu và có khóa chính kép (id, created_at), nên ghi bằng JdbcTemplate
// cho đơn giản thay vì map entity JPA.
@Service
public class UserEventService {

	private final JdbcTemplate jdbc;
	private final ObjectMapper mapper;

	public UserEventService(JdbcTemplate jdbc, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.mapper = mapper;
	}

	// Tham gia transaction của nơi gọi, giống JobService.enqueue
	public void record(Long userId, String eventType, Object payload) {
		jdbc.update("insert into user_events (user_id, event_type, payload, created_at) values (?, ?, ?::jsonb, now())",
				userId, eventType, mapper.writeValueAsString(payload));
	}
}
