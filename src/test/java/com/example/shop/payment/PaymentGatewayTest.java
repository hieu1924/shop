package com.example.shop.payment;

import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Server giả cho phép chọn kiểu phản hồi để chứng minh retry, circuit breaker và fallback thật sự chạy
@SpringBootTest
class PaymentGatewayTest {

	static final HttpServer server;
	static final AtomicInteger requests = new AtomicInteger();
	static final List<String> idempotencyKeys = new CopyOnWriteArrayList<>();
	static volatile int status = 200;
	static volatile long delayMs = 0;

	static {
		try {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		server.createContext("/charges", exchange -> {
			requests.incrementAndGet();
			idempotencyKeys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
			exchange.getRequestBody().readAllBytes();
			try {
				Thread.sleep(delayMs);
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
			byte[] body = "{\"orderId\":7,\"status\":\"SUCCEEDED\"}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			try {
				exchange.sendResponseHeaders(status, status == 200 ? body.length : -1);
				if (status == 200) {
					exchange.getResponseBody().write(body);
				}
			} catch (IOException ignored) {
				// client đã bỏ cuộc vì timeout
			} finally {
				exchange.close();
			}
		});
		// Nhiều luồng: nếu một handler đang ngủ (mô phỏng chậm), các request sau vẫn được xử lý và đếm ngay
		server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
		server.start();
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("app.payment.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
		registry.add("app.payment.read-timeout", () -> "300ms");
	}

	@AfterAll
	static void stop() {
		server.stop(0);
	}

	@Autowired PaymentGateway gateway;
	@Autowired CircuitBreakerRegistry breakers;

	static final ChargeRequest CHARGE = new ChargeRequest(7L, new BigDecimal("100.00"));

	@BeforeEach
	void reset() {
		breakers.circuitBreaker("payment").reset();
		requests.set(0);
		idempotencyKeys.clear();
		status = 200;
		delayMs = 0;
	}

	@Test
	void successfulCallSendsIdempotencyKey() {
		var result = gateway.charge(CHARGE);

		assertThat(result.status()).isEqualTo("SUCCEEDED");
		assertThat(idempotencyKeys).containsExactly("order-7");
	}

	@Test
	void serviceUnavailableIsRetriedThreeTimesThenFallsBackToPending() {
		status = 503;

		var result = gateway.charge(CHARGE);

		assertThat(result.isPending()).isTrue();
		assertThat(requests.get()).isEqualTo(3);
		assertThat(idempotencyKeys).containsOnly("order-7");   // mọi lần retry dùng cùng key
	}

	@Test
	void timeoutIsRetriedThenFallsBackToPending() {
		delayMs = 1000;   // chậm hơn read-timeout 300ms

		var result = gateway.charge(CHARGE);

		assertThat(result.isPending()).isTrue();
		assertThat(requests.get()).isEqualTo(3);
	}

	@Test
	void clientErrorIsNotRetriedNorHiddenByFallbackNorCountedAgainstTheBreaker() {
		status = 400;

		assertThatThrownBy(() -> gateway.charge(CHARGE)).isInstanceOf(HttpClientErrorException.class);

		assertThat(requests.get()).isEqualTo(1);
		assertThat(breakers.circuitBreaker("payment").getMetrics().getNumberOfFailedCalls()).isZero();
	}

	@Test
	void breakerOpensAfterRepeatedFailuresAndStopsCallingThePartner() {
		status = 503;
		for (int i = 0; i < 4; i++) {   // 4 lần charge x 3 lần thử = 12 lần gọi lỗi >= minimum-number-of-calls (10)
			gateway.charge(CHARGE);
		}
		assertThat(breakers.circuitBreaker("payment").getState()).isEqualTo(CircuitBreaker.State.OPEN);
		int before = requests.get();

		var result = gateway.charge(CHARGE);

		assertThat(result.isPending()).isTrue();
		assertThat(requests.get()).isEqualTo(before);   // mạch mở: không gọi đối tác nữa
	}
}
