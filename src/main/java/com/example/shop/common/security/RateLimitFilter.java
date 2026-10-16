package com.example.shop.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/**
 * Giới hạn số request mỗi phút theo user (đã đăng nhập) hoặc theo IP, đếm bằng Redis để mọi instance dùng
 * chung bộ đếm. Thuật toán fixed window: dễ hiểu nhưng cho phép bùng nổ gấp đôi tại ranh giới hai phút;
 * khi cần chính xác hơn (token bucket, giới hạn theo gói dịch vụ) dùng Bucket4j với backend Redis.
 *
 * Không khai báo là bean: bean Filter sẽ bị Spring Boot tự đăng ký vào servlet container và chạy TRƯỚC
 * Spring Security, lúc đó chưa biết user là ai. SecurityConfig tự tạo và gắn nó vào filter chain.
 */
public class RateLimitFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

	private final StringRedisTemplate redis;
	private final int limitPerMinute;

	public RateLimitFilter(StringRedisTemplate redis, int limitPerMinute) {
		this.redis = redis;
		this.limitPerMinute = limitPerMinute;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return request.getRequestURI().startsWith("/actuator/");   // health check của load balancer không bị giới hạn
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		long window = Instant.now().getEpochSecond() / 60;
		String key = "rl:" + client(request) + ":" + window;

		Long count;
		try {
			count = redis.opsForValue().increment(key);
			if (count != null && count == 1) {
				redis.expire(key, Duration.ofSeconds(90));
			}
		} catch (DataAccessException e) {
			// Redis lỗi: cho qua, đừng chặn toàn bộ người dùng chỉ vì bộ đếm hỏng
			log.warn("Rate limit bỏ qua vì Redis lỗi: {}", e.getMessage());
			chain.doFilter(request, response);
			return;
		}

		if (count != null && count > limitPerMinute) {
			response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
			response.setHeader("Retry-After", "60");
			return;
		}
		chain.doFilter(request, response);
	}

	private String client(HttpServletRequest request) {
		// Filter nằm sau bước xác thực Bearer trong chain nên đã biết user là ai.
		// getRemoteAddr() là IP thật của client nhờ server.forward-headers-strategy (mục 2.5).
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
			return "u:" + auth.getName();
		}
		return "ip:" + request.getRemoteAddr();
	}
}
