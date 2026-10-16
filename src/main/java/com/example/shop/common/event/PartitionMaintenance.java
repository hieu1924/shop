package com.example.shop.common.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

// Tạo trước partition cho các tháng sắp tới để dữ liệu không rơi vào partition default.
// Thay cho việc nhớ làm tay; nếu bảng lớn hơn, dùng extension pg_partman.
@Component
public class PartitionMaintenance {

	private static final Logger log = LoggerFactory.getLogger(PartitionMaintenance.class);
	private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyy_MM");
	private static final int MONTHS_AHEAD = 3;

	private final JdbcTemplate jdbc;

	public PartitionMaintenance(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@EventListener(ApplicationReadyEvent.class)   // chạy khi khởi động, phòng trường hợp lịch tháng bị lỡ
	@Scheduled(cron = "0 0 3 1 * *")              // 03:00 ngày 1 hằng tháng
	public void ensurePartitions() {
		YearMonth start = YearMonth.now(ZoneOffset.UTC);
		for (int i = 0; i <= MONTHS_AHEAD; i++) {
			createIfMissing(start.plusMonths(i));
		}
	}

	private void createIfMissing(YearMonth month) {
		String name = "user_events_" + month.format(SUFFIX);
		// Tên và ngày đều sinh từ YearMonth, không có dữ liệu người dùng nên ghép chuỗi an toàn.
		String sql = "create table if not exists " + name + " partition of user_events for values from ('"
				+ month.atDay(1) + " 00:00:00+00') to ('" + month.plusMonths(1).atDay(1) + " 00:00:00+00')";
		try {
			jdbc.execute(sql);
		} catch (DataAccessException e) {
			// Nhiều instance cùng chạy, hoặc dòng đã nằm trong partition default: ghi log, không làm app chết
			log.warn("Không tạo được partition {}", name, e);
		}
	}
}
