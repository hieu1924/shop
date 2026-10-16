package com.example.shop.common.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, UUID> {

	// 1 = lần đầu thấy message này, 0 = đã xử lý rồi
	@Modifying
	@Query(value = """
			INSERT INTO processed_messages (id, processed_at)
			VALUES (:id, now())
			ON CONFLICT (id) DO NOTHING
			""", nativeQuery = true)
	int markProcessed(@Param("id") UUID id);
}
