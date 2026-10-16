package com.example.shop.common.job;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Duration;
import java.time.Instant;

@Entity
@Getter
@Table(name = "jobs")
public class Job {

	private static final int MAX_ATTEMPTS = 5;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String type;
	private String payload;

	@Enumerated(EnumType.STRING)
	private JobStatus status = JobStatus.PENDING;

	private int attempts;
	private Instant runAt = Instant.now();
	private String lastError;
	private Instant createdAt = Instant.now();

	protected Job() { }

	public Job(String type, String payload) {
		this.type = type;
		this.payload = payload;
	}

	public void markDone() {
		status = JobStatus.DONE;
	}

	public void markFailed(String error) {
		attempts++;
		lastError = error;
		if (attempts >= MAX_ATTEMPTS) {
			status = JobStatus.FAILED;          // cần người xem xét
		} else {
			// exponential backoff: 1, 2, 4, 8 phút
			runAt = Instant.now().plus(Duration.ofMinutes(1L << (attempts - 1)));
		}
	}
}
