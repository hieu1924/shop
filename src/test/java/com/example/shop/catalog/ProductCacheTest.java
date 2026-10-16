package com.example.shop.catalog;

import com.example.shop.support.SqlCounter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// KHÔNG @Transactional: cache là transactionAware nên chỉ ghi/xóa sau commit thật.
// Cần Redis đang chạy: docker compose up -d
@SpringBootTest(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.example.shop.support.SqlCounter")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ProductCacheTest {

	@Autowired ProductService service;
	@Autowired ProductRepository repo;
	@Autowired MockMvc mvc;

	Long id;

	@BeforeEach
	void setUp() {
		id = repo.save(new Product("Cache test", new BigDecimal("10.00"), 1L)).getId();
	}

	@AfterEach
	void tearDown() {
		service.updatePrice(id, new BigDecimal("10.00"));   // xóa entry cache của sản phẩm sắp bị xóa
		repo.deleteById(id);
	}

	@Test
	void secondReadComesFromCacheAndPriceUpdateEvictsIt() {
		SqlCounter.reset();

		var first = service.getProduct(id);
		var second = service.getProduct(id);

		assertThat(second).isEqualTo(first);
		assertThat(SqlCounter.statements()).hasSize(1);   // lần 2 không chạm DB

		service.updatePrice(id, new BigDecimal("12.50"));

		assertThat(repo.findById(id).orElseThrow().getPrice()).isEqualByComparingTo("12.50");
		assertThat(service.getProduct(id).price()).isEqualByComparingTo("12.50");
	}

	@Test
	void updatingPriceNeedsCatalogWriteScope() throws Exception {
		String body = "{\"price\": 99.00}";
		String plain = token(null);
		String admin = token("catalog:write");

		mvc.perform(put("/api/products/" + id + "/price").contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isUnauthorized());
		mvc.perform(put("/api/products/" + id + "/price").contentType(MediaType.APPLICATION_JSON)
						.header("Authorization", "Bearer " + plain).content(body))
				.andExpect(status().isForbidden());
		mvc.perform(put("/api/products/" + id + "/price").contentType(MediaType.APPLICATION_JSON)
						.header("Authorization", "Bearer " + admin).content(body))
				.andExpect(status().isOk());
		mvc.perform(get("/api/products/" + id)).andExpect(jsonPath("$.price").value(99.0));
	}

	private String token(String scope) throws Exception {
		var req = get("/dev/token").param("userId", "1");
		if (scope != null) {
			req.param("scope", scope);
		}
		return mvc.perform(req).andReturn().getResponse().getContentAsString();
	}
}
