package com.example.shop.common.security;

import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

// GET /dev/token?userId=1 -> JWT hợp lệ 1 giờ cho user đó (sub = user id). Chỉ có ở profile dev.
@RestController
@Profile("dev")
class DevTokenController {

	private final JwtEncoder encoder;

	DevTokenController(JwtEncoder encoder) {
		this.encoder = encoder;
	}

	@GetMapping("/dev/token")
	String token(@RequestParam Long userId, @RequestParam(required = false) String scope) {
		Instant now = Instant.now();
		JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
				.subject(String.valueOf(userId))
				.issuedAt(now)
				.expiresAt(now.plus(Duration.ofHours(1)));
		if (scope != null) {
			builder.claim("scope", scope);   // vd: catalog:write để được đổi giá sản phẩm
		}
		JwtClaimsSet claims = builder.build();
		JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
		return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}
}
