# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Spring Boot 4.1.1 / Java 21 (Gradle toolchain) app, package `com.example.shop`. Stack: Spring MVC (`webmvc`), Spring Data JPA, Flyway migrations with PostgreSQL, Validation, Mail, Actuator, Sentry (`sentryVersion` in `build.gradle`), Lombok. Currently only a skeleton (`ShopApplication`, one context-load test); `docker-compose.yml` runs PostgreSQL (db `shop`, host port 5435; user/password from `DB_USER`/`DB_PASSWORD`, default `shop`): `docker compose up -d`.

## Commands

Use the Gradle wrapper (on Windows PowerShell: `.\gradlew.bat`).

- Build: `./gradlew build`
- Run: `./gradlew bootRun`
- All tests: `./gradlew test`
- Single test class: `./gradlew test --tests com.example.shop.ShopApplicationTests`
- Single method: `./gradlew test --tests 'com.example.shop.ShopApplicationTests.methodName'`

Flyway and JPA need the PostgreSQL from `docker-compose.yml` running (datasource in `application.yml` reads `DB_HOST`, `DB_USER`, `DB_PASSWORD` (no password default; set `DB_PASSWORD=shop` locally)); the app and context tests fail to start without it.

## Tool usage (read / search / glob)

- Delegate file reading, code search, and globbing to the Haiku-backed `haiku-search` subagent (`.claude/agents/haiku-search.md`; tools: Read, Grep, Glob) to save cost.
- Use `rig-grep` for content search.

## Architecture

Modular monolith following `Scale backend với Spring Boot từ 100 đến 1 triệu người dùng.md` (the roadmap; phase 1 is implemented, phases 2-3 are not). Code is split by business module, not by layer: `user`, `catalog`, `order` under `com.example.shop`, plus `common/error` (`ProblemDetail` handler) and `common/job`. A module calls another only through its public service (e.g. `OrderService` -> `ProductService.getEntity`), never its repository.

- **Background jobs:** durable `jobs` table (Flyway `V2`) polled by `JobWorker` (`@Scheduled`, `FOR UPDATE SKIP LOCKED`, exponential backoff, `FAILED` after 5 attempts). `JobService.enqueue` joins the caller's transaction, so a job exists iff the business write committed. Add a job type by implementing `JobHandler` as a `@Component`.
- **Schema:** only Flyway migrations (`src/main/resources/db/migration`); `ddl-auto: validate`.
- **Boot 4 differences from the article:** Jackson 3 (`tools.jackson.databind.ObjectMapper`, `JacksonException` is unchecked) and `sentry-spring-boot-4-starter` instead of the `-jakarta` one.
- **No auth yet:** `POST /api/orders` takes the user from the `X-User-Id` header until JWT (article section 2.4).
- Order confirmation emails need an SMTP server (`MAIL_HOST`/`MAIL_PORT`, default `localhost:1025`); without one jobs just retry with backoff. Mail health is disabled so it can't flip `/actuator/health`.
