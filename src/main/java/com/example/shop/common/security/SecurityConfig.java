package com.example.shop.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	SecurityFilterChain api(HttpSecurity http) throws Exception {
		http
				.csrf(csrf -> csrf.disable())   // API stateless dùng Bearer token, không dùng cookie
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/actuator/health/**").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/products/**").permitAll()
						.requestMatchers(HttpMethod.PUT, "/api/products/**").hasAuthority("SCOPE_catalog:write")
						.requestMatchers("/dev/**").permitAll()   // chỉ tồn tại khi chạy profile dev
						.anyRequest().authenticated())
				.oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));
		return http.build();
	}
}
