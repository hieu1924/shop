package com.example.shop.common.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class SecurityTest {

	@Autowired MockMvc mvc;

	@Test
	void ordersRequireToken() throws Exception {
		mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
	}

	@Test
	void productsArePublic() throws Exception {
		mvc.perform(get("/api/products")).andExpect(status().isOk());
	}

	@Test
	void tokenFromDevEndpointIsAccepted() throws Exception {
		String token = mvc.perform(get("/dev/token").param("userId", "999999"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

		mvc.perform(get("/api/orders").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());
	}

	@Test
	void tokenSignedWithAnotherKeyIsRejected() throws Exception {
		RSAKey other = new RSAKeyGenerator(2048).generate();
		var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(other)));
		var claims = JwtClaimsSet.builder().subject("1")
				.issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
		String forged = encoder.encode(JwtEncoderParameters.from(
				JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();

		mvc.perform(get("/api/orders").header("Authorization", "Bearer " + forged))
				.andExpect(status().isUnauthorized());
	}
}
