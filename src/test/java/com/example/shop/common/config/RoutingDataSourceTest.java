package com.example.shop.common.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RoutingDataSourceTest {

	@Autowired JdbcTemplate jdbc;
	@Autowired PlatformTransactionManager txManager;
	@Autowired @Qualifier("primaryDataSource") HikariDataSource primary;
	@Autowired @Qualifier("replicaDataSource") HikariDataSource replica;

	private String connectedPool(boolean readOnly) {
		var tx = new TransactionTemplate(txManager);
		tx.setReadOnly(readOnly);
		return tx.execute(status -> jdbc.queryForObject("select current_setting('application_name')", String.class));
	}

	@Test
	void readOnlyTransactionUsesReplicaAndWriteTransactionUsesPrimary() {
		assertThat(connectedPool(true)).isEqualTo("shop-replica");
		assertThat(connectedPool(false)).isEqualTo("shop-primary");
	}

	@Test
	void poolSettingsComeFromAppDatasourceProperties() {
		assertThat(primary.getMaximumPoolSize()).isEqualTo(20);
		assertThat(primary.getConnectionTimeout()).isEqualTo(3000);
		assertThat(replica.getMaximumPoolSize()).isEqualTo(30);
		assertThat(replica.isReadOnly()).isTrue();
	}
}
