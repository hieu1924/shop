package com.example.shop.common.event;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional   // mọi dòng và DDL trong test được rollback
class PartitioningTest {

	@Autowired JdbcTemplate jdbc;
	@Autowired PartitionMaintenance maintenance;
	@Autowired UserEventService events;

	private String partitionOf(String createdAt) {
		jdbc.update("insert into user_events (user_id, event_type, created_at) values (-1, 'T', ?::timestamptz)", createdAt);
		return jdbc.queryForObject(
				"select tableoid::regclass::text from user_events where user_id = -1 and created_at = ?::timestamptz",
				String.class, createdAt);
	}

	@Test
	void rowsLandInThePartitionOfTheirMonth() {
		assertThat(partitionOf("2026-11-15 10:00:00+00")).isEqualTo("user_events_2026_11");
		assertThat(partitionOf("2026-12-31 23:59:59+00")).isEqualTo("user_events_2026_12");
	}

	@Test
	void rowOutsideAnyPartitionFallsIntoDefault() {
		assertThat(partitionOf("2099-01-01 00:00:00+00")).isEqualTo("user_events_default");
	}

	@Test
	void queryWithTimeRangeOnlyScansRelevantPartition() {
		List<String> plan = jdbc.queryForList(
				"explain select * from user_events where created_at >= '2026-11-05 00:00:00+00' and created_at < '2026-11-06 00:00:00+00'",
				String.class);
		String text = String.join("\n", plan);

		assertThat(text).contains("user_events_2026_11");
		assertThat(text).doesNotContain("user_events_2026_10").doesNotContain("user_events_2026_12");
	}

	@Test
	void maintenanceCreatesPartitionsAhead() {
		maintenance.ensurePartitions();

		YearMonth target = YearMonth.now(ZoneOffset.UTC).plusMonths(3);
		String name = "user_events_" + target.format(DateTimeFormatter.ofPattern("yyyy_MM"));
		assertThat(jdbc.queryForObject("select to_regclass(?)::text", String.class, name)).isEqualTo(name);
	}

	@Test
	void recordWritesJsonbPayload() {
		events.record(-2L, "TEST", java.util.Map.of("k", "v"));

		String v = jdbc.queryForObject("select payload->>'k' from user_events where user_id = -2", String.class);
		assertThat(v).isEqualTo("v");
	}
}
