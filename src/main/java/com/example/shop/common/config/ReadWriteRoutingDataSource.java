package com.example.shop.common.config;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// @Transactional(readOnly = true) -> replica, còn lại -> primary
public class ReadWriteRoutingDataSource extends AbstractRoutingDataSource {

	public static final String PRIMARY = "primary";
	public static final String REPLICA = "replica";

	@Override
	protected Object determineCurrentLookupKey() {
		return TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? REPLICA : PRIMARY;
	}
}
