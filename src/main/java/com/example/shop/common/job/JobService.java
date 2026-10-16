package com.example.shop.common.job;

import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class JobService {

	private final JobRepository jobs;
	private final ObjectMapper mapper;

	public JobService(JobRepository jobs, ObjectMapper mapper) {
		this.jobs = jobs;
		this.mapper = mapper;
	}

	// Không có @Transactional riêng: tham gia transaction của nơi gọi
	public void enqueue(String type, Object payload) {
		try {
			jobs.save(new Job(type, mapper.writeValueAsString(payload)));
		} catch (JacksonException e) {
			throw new IllegalArgumentException("Payload không serialize được", e);
		}
	}
}
