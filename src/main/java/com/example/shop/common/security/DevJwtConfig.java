package com.example.shop.common.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

// Chỉ để dev tự ký token thử. Production không có profile dev nên bean này không tồn tại.
@Configuration
@Profile("dev")
class DevJwtConfig {

	@Bean
	JwtEncoder devJwtEncoder(@Value("${app.dev.private-key-location}") Resource privateKey,
							 @Value("${spring.security.oauth2.resourceserver.jwt.public-key-location}") Resource publicKey)
			throws IOException {
		RSAPrivateKey priv;
		RSAPublicKey pub;
		try (InputStream in = privateKey.getInputStream()) {
			priv = RsaKeyConverters.pkcs8().convert(in);
		}
		try (InputStream in = publicKey.getInputStream()) {
			pub = RsaKeyConverters.x509().convert(in);
		}
		RSAKey jwk = new RSAKey.Builder(pub).privateKey(priv).build();
		return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
	}
}
