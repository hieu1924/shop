package com.example.shop.common.config;

import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;

import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

	public static final String PRODUCTS = "products";

	@Bean
	RedisCacheManagerBuilderCustomizer cacheCustomizer() {
		return builder -> builder
				// Chỉ ghi/xóa cache sau khi transaction commit thành công
				.transactionAware()
				.enableStatistics()   // bật để có metric hit/miss (cache.gets)
				.withCacheConfiguration(PRODUCTS,
						RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofMinutes(10)));
	}

	// Redis chết hoặc chậm: ghi log rồi đọc thẳng DB, thay vì làm request lỗi 500.
	@Override
	public CacheErrorHandler errorHandler() {
		return new LoggingCacheErrorHandler();
	}
}
