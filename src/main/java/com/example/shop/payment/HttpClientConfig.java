package com.example.shop.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
class HttpClientConfig {

	// Timeout cho MỌI lời gọi ra ngoài: không có timeout thì một đối tác chậm sẽ giữ thread và kết nối DB
	// của mình cho đến khi cả hệ thống nghẽn theo.
	@Bean
	RestClient paymentClient(@Value("${app.payment.base-url}") String baseUrl,
							 @Value("${app.payment.connect-timeout}") Duration connectTimeout,
							 @Value("${app.payment.read-timeout}") Duration readTimeout) {
		// Dùng java.net.http thay vì SimpleClientHttpRequestFactory (HttpURLConnection): với bản đó, read timeout
		// bị RestClient bọc thành RestClientException chứ không phải ResourceAccessException, nên retry-exceptions
		// ở application.yml không bắt được và timeout sẽ không bao giờ được retry.
		var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connectTimeout).build());
		factory.setReadTimeout(readTimeout);
		return RestClient.builder()
				.baseUrl(baseUrl)
				.requestFactory(factory)
				.build();
	}
}
