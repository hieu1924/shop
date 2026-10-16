package com.example.shop.common.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Cần Redis đang chạy (docker compose up -d)
// timeout Redis nới ra 3s: một lần kết nối lại chậm (Docker trên Windows) sẽ khiến filter fail-open và test thấy 200 thay vì 429
@SpringBootTest(properties = {"app.rate-limit.per-minute=3", "spring.data.redis.timeout=3s"})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class RateLimitTest {

	@Autowired MockMvc mvc;

	private String tokenFor(long userId) throws Exception {
		// IP ngẫu nhiên: bộ đếm Redis dùng chung với các test khác nên 127.0.0.1 có thể đã vượt giới hạn 3 trong phút này
		return mvc.perform(get("/dev/token").param("userId", String.valueOf(userId)).with(fromIp(randomIp())))
				.andReturn().getResponse().getContentAsString();
	}

	// Fixed window: nếu test rơi vào ranh giới hai phút thì bộ đếm bị tách đôi. Chờ sang cửa sổ mới cho chắc.
	private void startOfFreshWindow() throws Exception {
		long second = java.time.Instant.now().getEpochSecond() % 60;
		if (second > 50) {
			Thread.sleep((61 - second) * 1000);
		}
		// khởi động lại kết nối Redis (nếu bị rớt trong lúc chờ) bằng một request không liên quan đến bộ đếm của test
		mvc.perform(get("/api/products").with(fromIp(randomIp())));
	}

	private static String randomIp() {
		return "10." + ThreadLocalRandom.current().nextInt(256) + "." + ThreadLocalRandom.current().nextInt(256) + "." + ThreadLocalRandom.current().nextInt(1, 255);
	}

	private static RequestPostProcessor fromIp(String ip) {
		return request -> {
			request.setRemoteAddr(ip);
			return request;
		};
	}

	@Test
	void authenticatedUserIsLimitedPerUserNotPerIp() throws Exception {
		startOfFreshWindow();
		long userA = 900_000_000L + ThreadLocalRandom.current().nextInt(1_000_000);
		long userB = userA + 1;
		String tokenA = tokenFor(userA);
		String tokenB = tokenFor(userB);

		for (int i = 0; i < 3; i++) {
			mvc.perform(get("/api/orders").header("Authorization", "Bearer " + tokenA)).andExpect(status().isOk());
		}
		mvc.perform(get("/api/orders").header("Authorization", "Bearer " + tokenA))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string("Retry-After", "60"));

		// cùng IP nhưng user khác vẫn đi được
		mvc.perform(get("/api/orders").header("Authorization", "Bearer " + tokenB)).andExpect(status().isOk());
	}

	@Test
	void anonymousClientIsLimitedPerIp() throws Exception {
		startOfFreshWindow();
		String ip = "10." + ThreadLocalRandom.current().nextInt(256) + "." + ThreadLocalRandom.current().nextInt(256) + ".7";

		for (int i = 0; i < 3; i++) {
			mvc.perform(get("/api/products").with(fromIp(ip))).andExpect(status().isOk());
		}
		mvc.perform(get("/api/products").with(fromIp(ip))).andExpect(status().isTooManyRequests());
		mvc.perform(get("/api/products").with(fromIp("10.250.250.250"))).andExpect(status().isOk());
	}

	@Test
	void healthCheckIsNeverLimited() throws Exception {
		String ip = "10.77." + ThreadLocalRandom.current().nextInt(256) + ".9";

		for (int i = 0; i < 6; i++) {
			mvc.perform(get("/actuator/health").with(fromIp(ip))).andExpect(status().is(org.hamcrest.Matchers.not(429)));
		}
	}

	@Test
	void redisFailureLetsRequestsThrough() throws Exception {
		StringRedisTemplate broken = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
		when(broken.opsForValue().increment(anyString())).thenThrow(new RedisConnectionFailureException("down"));
		var filter = new RateLimitFilter(broken, 3);
		FilterChain chain = mock(FilterChain.class);
		var response = new MockHttpServletResponse();

		filter.doFilter(new MockHttpServletRequest("GET", "/api/orders"), response, chain);

		verify(chain).doFilter(any(), any());
		assertThat(response.getStatus()).isEqualTo(200);
	}
}
