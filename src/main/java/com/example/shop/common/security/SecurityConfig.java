package com.example.shop.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	SecurityFilterChain api(HttpSecurity http, StringRedisTemplate redis,
							@Value("${app.rate-limit.per-minute}") int rateLimitPerMinute) throws Exception {
		http
				.csrf(csrf -> csrf.disable())   // API stateless dùng Bearer token, không dùng cookie
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/actuator/health/**").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/products/**").permitAll()
						.requestMatchers(HttpMethod.PUT, "/api/products/**").hasAuthority("SCOPE_catalog:write")
						.requestMatchers("/dev/**").permitAll()   // chỉ tồn tại khi chạy profile dev
						.anyRequest().authenticated())
				.oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
				// Sau khi token được xác thực để biết đang giới hạn cho user nào (mục 3.6)
				.addFilterAfter(new RateLimitFilter(redis, rateLimitPerMinute), BearerTokenAuthenticationFilter.class);
		return http.build();
	}
}
