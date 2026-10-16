package com.example.shop.common.outbox;

import jakarta.persistence.*;
import lombok.Getter;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@Table(name = "outbox_events")
public class OutboxEvent implements Persistable<UUID> {

	@Id
	private UUID id;
	private String routingKey;
	private String payload;
	private Instant createdAt;
	private Instant publishedAt;

	// id do mình tự sinh nên Spring Data không phân biệt được "mới" hay "đã có": nếu không khai báo
	// Persistable, save() sẽ chạy thêm một SELECT (merge) trước mỗi INSERT.
	@Transient
	private boolean isNew = true;

	protected OutboxEvent() { }

	public static OutboxEvent of(String routingKey, String payload) {
		var e = new OutboxEvent();
		e.id = UUID.randomUUID();       // cũng là message id để consumer chống trùng
		e.routingKey = routingKey;
		e.payload = payload;
		e.createdAt = Instant.now();
		return e;
	}

	public void markPublished() {
		publishedAt = Instant.now();
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	@PostPersist
	@PostLoad
	void markNotNew() {
		isNew = false;
	}
}
