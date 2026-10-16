package com.example.shop.common.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface JobRepository extends JpaRepository<Job, Long> {

	// FOR UPDATE SKIP LOCKED: mỗi worker khóa một lô job khác nhau,
	// nên chạy nhiều instance app (giai đoạn 2) cũng không xử lý trùng.
	@Query(value = """
			SELECT * FROM jobs
			WHERE status = 'PENDING' AND run_at <= now()
			ORDER BY run_at
			LIMIT :limit
			FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	List<Job> lockNextBatch(@Param("limit") int limit);
}
