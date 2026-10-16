package com.example.shop.common;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureMetrics   // mặc định test dùng registry giả, không bật endpoint prometheus
class ObservabilityTest {

	@Autowired MockMvc mvc;

	// Mỗi test một IP riêng để không dính rate limit dùng chung với các test khác
	private static RequestPostProcessor randomIp() {
		String ip = "10.55." + ThreadLocalRandom.current().nextInt(256) + "." + ThreadLocalRandom.current().nextInt(1, 255);
		return request -> {
			request.setRemoteAddr(ip);
			return request;
		};
	}

	@Test
	void prometheusEndpointExposesHttpDatabaseCircuitBreakerAndBusinessMetrics() throws Exception {
		mvc.perform(get("/api/products").with(randomIp())).andExpect(status().isOk());   // tạo ít nhất một mẫu http.server.requests

		String body = mvc.perform(get("/actuator/prometheus").with(randomIp()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

		assertThat(body)
				.contains("http_server_requests_seconds_bucket")   // histogram để tính p95/p99
				.contains("hikaricp_connections_active{pool=\"shop-primary\"")
				.contains("hikaricp_connections_active{pool=\"shop-replica\"")
				.contains("resilience4j_circuitbreaker_state{name=\"payment\"")
				.contains("shop_orders_placed_total");
	}

	@Test
	void kubernetesProbesAreAvailable() throws Exception {
		mvc.perform(get("/actuator/health/liveness").with(randomIp()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
		mvc.perform(get("/actuator/health/readiness").with(randomIp()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
	}
}
