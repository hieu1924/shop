# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Spring Boot 4.1.1 / Java 21 (Gradle toolchain) app, package `com.example.shop`. Stack: Spring MVC (`webmvc`), Spring Data JPA, Flyway migrations with PostgreSQL, Validation, Mail, Actuator, Sentry (`sentryVersion` in `build.gradle`), Lombok, plus (phases 2-3) Spring Security OAuth2 resource server, Spring Cache + Redis, Spring AMQP (RabbitMQ), Resilience4j (`resilience4jVersion`), Micrometer/Prometheus, OpenTelemetry. `docker-compose.yml` runs the backing services: `db` (PostgreSQL 17, db `shop`, host port 5435; user/password from `DB_USER`/`DB_PASSWORD`, default `shop`), `redis` (6379), `rabbitmq` (5672, UI 15672): `docker compose up -d`.

## Commands

Use the Gradle wrapper (on Windows PowerShell: `.\gradlew.bat`).

- Build: `./gradlew build`
- Run: `SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun` (the `dev` profile enables `GET /dev/token`, see Auth below)
- All tests: `./gradlew test`
- Single test class: `./gradlew test --tests com.example.shop.order.OrderQueryTest`
- Single method: `./gradlew test --tests 'com.example.shop.order.OrderQueryTest.methodName'`

Flyway and JPA need the PostgreSQL from `docker-compose.yml` running (datasource in `application.yml` reads `DB_HOST`, `DB_PORT`, `DB_USER`, `DB_PASSWORD` (no password default; set `DB_PASSWORD=shop` locally)); the app and context tests fail to start without it. **Tests also need Redis and RabbitMQ up** (`OrderEventsFlowTest`, `ProductCacheTest`, `RateLimitTest` use the real ones) and no other copy of the app consuming the `email.order-created` queue (e.g. a running `bootRun`), or messages get stolen. If port 8080 is already taken, run with `SERVER_PORT=8089`.

Environment gotcha (Windows): `JAVA_HOME` may point at JDK 8, which Gradle cannot run on. A JDK 21 lives in `~/.jdks/ms-21.0.12.1`; set `JAVA_HOME` to it.

## Tool usage (read / search / glob)

- Delegate file reading, code search, and globbing to the Haiku-backed `haiku-search` subagent (`.claude/agents/haiku-search.md`; tools: Read, Grep, Glob) to save cost.
- Use `rig-grep` for content search.

## Architecture

Modular monolith following `Scale backend với Spring Boot từ 100 đến 1 triệu người dùng.md` (the roadmap; phases 1-3 are implemented, except 3.3 sharding which is intentionally not built, and ShedLock from 2.5 which has no task to protect yet). Code is split by business module, not by layer: `user`, `catalog`, `order`, `payment` under `com.example.shop`, plus `common/{error,job,security,config,event,outbox,messaging}`. A module calls another only through its public service (e.g. `OrderService` -> `ProductService.getEntity`), never its repository. Commits on branch `phase-2-3` are one per article section (2.1 ... 3.8), meant to be read in order.

- **Schema:** only Flyway migrations (`src/main/resources/db/migration`); `ddl-auto: validate`. Never edit an applied migration. The article's numbers (V3 indexes, V10, V11) are shifted to follow V1-V3: V4 indexes, V5 partitioned `user_events`, V6 outbox + `processed_messages`.
- **Boot 4 differences from the article:** Jackson 3 (`tools.jackson.databind.ObjectMapper`, `JacksonException` is unchecked); `sentry-spring-boot-4-starter`; modular starters (`spring-boot-starter-security-oauth2-resource-server`, `-opentelemetry`, `-aspectj`, ...); `resilience4j-spring-boot4`; `RedisCacheManagerBuilderCustomizer` is in `org.springframework.boot.cache.autoconfigure`; renamed properties (`spring.rabbitmq.listener.simple.retry.max-retries` not `max-attempts`, `management.opentelemetry.tracing.export.otlp.endpoint` and `management.tracing.export.otlp.enabled`). Check property names against the jar's `META-INF/spring-configuration-metadata.json`; wrong names fail silently.
- **Auth (2.4):** stateless JWT. Public key from `JWT_PUBLIC_KEY_LOCATION` (default `classpath:jwt/dev-public.pem`, a DEV key: production must override it). User id = `sub` claim. With profile `dev`, `GET /dev/token?userId=1[&scope=catalog:write]` mints a token signed by `dev/dev-private.pem` (see `dev/README.md`). `PUT /api/products/{id}/price` needs scope `catalog:write`. `GET /api/products/**`, `/actuator/health/**`, `/actuator/prometheus` are public.
- **Database (2.1-2.3, 3.1, 3.2):** two Hikari pools configured under `app.datasource.{primary,replica}` (not `spring.datasource`); `@Transactional(readOnly = true)` routes to the replica via `ReadWriteRoutingDataSource` + `LazyConnectionDataSourceProxy`, everything else (and Flyway) to the primary. The replica URL defaults to the primary, so locally there is no real replica; `ApplicationName` in the URL tells the pools apart. `user_events` is range-partitioned by month (UTC) with a `DEFAULT` partition; `PartitionMaintenance` creates the next 3 months at startup and monthly.
- **Cache (2.6):** `products` cache in Redis, `transactionAware()`, fail-open (`LoggingCacheErrorHandler`). Cached values are `ProductDto` (must stay `Serializable`; rename the cache when its shape changes).
- **Events (3.4):** `OrderService.placeOrder` writes an `outbox_events` row in the order's transaction; `OutboxRelay` (`SKIP LOCKED`, publisher confirms) publishes to exchange `shop.events`; `OrderCreatedEmailListener` consumes `email.order-created` idempotently (`processed_messages`), retries 3 times then dead-letters to `email.order-created.dlq`. Order confirmation emails therefore need an SMTP server (`MAIL_HOST`/`MAIL_PORT`, default `localhost:1025`); without one messages end up in the DLQ. The Phase 1 `jobs` table/`JobWorker` remain as generic infrastructure but have no handlers now.
- **Resilience (3.5):** `PaymentGateway` (timeouts, Resilience4j retry outside circuit breaker, idempotency key, fallback `PENDING`). It uses `JdkClientHttpRequestFactory` because with `HttpURLConnection` read timeouts surface as `RestClientException` and are never retried. Nothing calls it yet and there is no real provider (`app.payment.base-url`).
- **Rate limit (3.6):** `RateLimitFilter` (Redis fixed window per user or IP, `app.rate-limit.per-minute`), added inside the security chain, deliberately not a bean. Fail-open when Redis errors.
- **Observability (3.7):** Prometheus at `/actuator/prometheus` (block `/actuator/` at the load balancer), liveness/readiness probes, counter `shop.orders.placed` (counted after commit), OTLP trace export off unless `TRACING_EXPORT_ENABLED=true`, JSON logs with profile `prod`. Redis/RabbitMQ/mail health indicators are disabled so they cannot flip `/actuator/health`.
- **Deploy (3.8):** `Dockerfile` (verified), `deploy/` nginx, pgbouncer and k8s samples (unverified, see `deploy/README.md`).
- **Test notes:** assert SQL counts with `SqlCounter` (per-thread), not Hibernate global statistics (the background `JobWorker`/relay add statements). Redis rate-limit counters are shared across cached test contexts, so tests use random IPs. Tests disable the Rabbit listener by default (`src/test/resources/application.properties`); `OrderEventsFlowTest` re-enables it.
