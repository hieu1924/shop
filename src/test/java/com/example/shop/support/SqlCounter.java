package com.example.shop.support;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.ArrayList;
import java.util.List;

/**
 * Ghi lại các câu SQL Hibernate chạy trên luồng hiện tại. Dùng thay cho Statistics.getPrepareStatementCount()
 * vì số đếm đó là toàn cục: JobWorker (@Scheduled) chạy nền sẽ làm sai số.
 * Bật bằng: spring.jpa.properties.hibernate.session_factory.statement_inspector=com.example.shop.support.SqlCounter
 */
public class SqlCounter implements StatementInspector {

	private static final ThreadLocal<List<String>> STATEMENTS = ThreadLocal.withInitial(ArrayList::new);

	public static void reset() {
		STATEMENTS.get().clear();
	}

	public static List<String> statements() {
		return List.copyOf(STATEMENTS.get());
	}

	@Override
	public String inspect(String sql) {
		STATEMENTS.get().add(sql);
		return sql;
	}
}
