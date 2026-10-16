package com.example.shop.common.messaging;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

// Chỉ để Hibernate validate schema; việc ghi dùng markProcessed (INSERT ... ON CONFLICT) trong repository.
@Entity
@Table(name = "processed_messages")
public class ProcessedMessage {

	@Id
	private UUID id;
	private Instant processedAt;

	protected ProcessedMessage() { }
}
