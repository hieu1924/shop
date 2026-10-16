package com.example.shop.common.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import javax.sql.DataSource;
import java.util.Map;

// Khi tự khai báo DataSource, Spring Boot không đọc spring.datasource.* nữa: cấu hình nằm ở app.datasource.*.
// JPA dùng bean @Primary (routing); Flyway dùng thẳng primary để migration không bao giờ đi qua replica.
@Configuration
public class DataSourceConfig {

	@Bean
	@FlywayDataSource
	@ConfigurationProperties("app.datasource.primary")
	HikariDataSource primaryDataSource() {
		return new HikariDataSource();
	}

	@Bean
	@ConfigurationProperties("app.datasource.replica")
	HikariDataSource replicaDataSource() {
		return new HikariDataSource();
	}

	@Bean
	@Primary
	DataSource dataSource(HikariDataSource primaryDataSource, HikariDataSource replicaDataSource) {
		var routing = new ReadWriteRoutingDataSource();
		routing.setTargetDataSources(Map.of(
				ReadWriteRoutingDataSource.PRIMARY, primaryDataSource,
				ReadWriteRoutingDataSource.REPLICA, replicaDataSource));
		routing.setDefaultTargetDataSource(primaryDataSource);
		routing.afterPropertiesSet();

		// Lazy: chỉ lấy kết nối thật khi câu SQL đầu tiên chạy, tức là SAU KHI Spring đã đánh dấu
		// transaction readOnly. Thiếu lớp này, routing luôn ra primary.
		return new LazyConnectionDataSourceProxy(routing);
	}
}
