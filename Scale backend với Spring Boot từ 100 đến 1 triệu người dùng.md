# Scale backend với Spring Boot: từ 100 đến 1 triệu người dùng

Oct 15, 2026 · @hieu

Bài này hiện thực lộ trình scale "đo trước, phức tạp sau" bằng Spring Boot: mỗi giai đoạn chỉ thêm đúng những thành phần mà số đo đòi hỏi, kèm code và cấu hình chạy được.

Nguyên tắc vẫn giữ nguyên như bản trước. Những thứ rẻ nhưng mất đi thì không lấy lại được (backup, giám sát, background job) có từ ngày đầu. Những thứ đắt và khó quay lại (Kubernetes, message broker, sharding) chỉ đến khi số đo (requests/giây, độ trễ p95, tải database) buộc phải làm.

**Tech stack dùng trong bài**

| Thành phần | Lựa chọn |
| --- | --- |
| Ngôn ngữ, runtime | Java 21 (virtual threads) |
| Framework | Spring Boot 3.5 (Spring Framework 6.2) |
| Database | PostgreSQL 16, migration bằng Flyway |
| Cache, rate limit | Redis |
| Message broker | RabbitMQ (Spring AMQP) |
| Resilience | Resilience4j |
| Observability | Spring Boot Actuator, Micrometer, Prometheus, OpenTelemetry |
| Build | Maven |

Ví dụ xuyên suốt là một ứng dụng bán hàng với các module user, catalog, order, payment. Code không dùng Lombok để bạn thấy rõ mọi thứ. Nếu dự án của bạn đã lên Spring Boot 4, phần lớn code vẫn giữ nguyên, nhưng một số tên starter và lớp Jackson có thay đổi, nên hãy đối chiếu với release notes chính thức.

## Giai đoạn 1 — Khoảng 100 người dùng: modular monolith trên một server

Một ứng dụng Spring Boot duy nhất, một PostgreSQL (nên là managed database), deploy lên một VPS hoặc PaaS. Đây là nền móng; các giai đoạn sau chỉ thêm vào chứ không phải viết lại.

### 1.1. Cấu trúc package theo nghiệp vụ

Chia code theo module nghiệp vụ thay vì theo lớp kỹ thuật (controller/service/repository). Mỗi module chỉ gọi module khác qua service công khai của nó, không truy cập thẳng repository của module khác.

```text
com.example.shop
├── ShopApplication.java
├── user/        UserController, UserService, User, UserRepository
├── catalog/     ProductController, ProductService, Product, ProductRepository
├── order/       OrderController, OrderService, Order, OrderItem, OrderRepository
├── payment/     PaymentService, PaymentGateway
└── common/
    ├── config/  SecurityConfig, ...
    ├── error/   GlobalExceptionHandler
    └── job/     Job, JobRepository, JobWorker, JobHandler
```

Nếu muốn kiểm tra tự động ranh giới giữa các module, có thể dùng Spring Modulith: nó viết test phát hiện module nào đang gọi vào phần nội bộ của module khác. Khi nào thật sự cần tách microservice, ranh giới này giúp việc tách đơn giản hơn nhiều.

### 1.2. Dependency

```xml
<!-- pom.xml: parent spring-boot-starter-parent, bản 3.5 với patch mới nhất -->
<dependencies>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-mail</artifactId>
  </dependency>
  <dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
  </dependency>
  <dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
  </dependency>
  <dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
  </dependency>
  <!-- Error tracking: lấy version mới nhất trên trang của Sentry -->
  <dependency>
    <groupId>io.sentry</groupId>
    <artifactId>sentry-spring-boot-starter-jakarta</artifactId>
    <version>${sentry.version}</version>
  </dependency>
</dependencies>
```

### 1.3. Cấu hình `application.yml`

```yaml
spring:
  application:
    name: shop
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:5432/shop
    username: ${DB_USER:shop}
    password: ${DB_PASSWORD}
  jpa:
    open-in-view: false          # tránh giữ kết nối DB suốt request
    hibernate:
      ddl-auto: validate         # schema do Flyway quản lý, không để Hibernate tự sửa
  threads:
    virtual:
      enabled: true              # Java 21: virtual threads cho Tomcat, @Async, @Scheduled

server:
  shutdown: graceful             # xử lý xong request đang chạy rồi mới tắt

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics

sentry:
  dsn: ${SENTRY_DSN:}
  traces-sample-rate: 0.1
```

Ba cấu hình đáng chú ý: `open-in-view: false` ngăn mỗi request giữ một kết nối database đến tận khi render xong response; `ddl-auto: validate` buộc mọi thay đổi schema phải đi qua migration; mật khẩu luôn lấy từ biến môi trường, không commit vào Git.

### 1.4. Migration với Flyway

Mọi thay đổi schema là một file SQL có đánh số trong `src/main/resources/db/migration`. Flyway tự chạy các file mới khi ứng dụng khởi động.

```sql
-- V1__init.sql
CREATE TABLE products (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(200)   NOT NULL,
    price       NUMERIC(12, 2) NOT NULL,
    category_id BIGINT         NOT NULL,
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE TABLE orders (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT         NOT NULL,
    status      VARCHAR(20)    NOT NULL,
    total       NUMERIC(12, 2) NOT NULL,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE TABLE order_items (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT         NOT NULL REFERENCES orders (id),
    product_id  BIGINT         NOT NULL REFERENCES products (id),
    quantity    INT            NOT NULL CHECK (quantity > 0),
    unit_price  NUMERIC(12, 2) NOT NULL
);
```

### 1.5. Entity, repository, service, controller

```java
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status = OrderStatus.PENDING;

    @Column(nullable = false)
    private BigDecimal total = BigDecimal.ZERO;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() { }

    public Order(Long userId) {
        this.userId = userId;
    }

    public void addItem(Product product, int quantity) {
        items.add(new OrderItem(this, product.getId(), quantity, product.getPrice()));
        total = total.add(product.getPrice().multiply(BigDecimal.valueOf(quantity)));
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public OrderStatus getStatus() { return status; }
    public BigDecimal getTotal() { return total; }
    public List<OrderItem> getItems() { return items; }
}
```

```java
public interface OrderRepository extends JpaRepository<Order, Long> {
}
```

```java
// DTO dạng record: API không bao giờ trả entity trực tiếp
public record PlaceOrderRequest(
        @NotEmpty List<@Valid Line> lines) {

    public record Line(@NotNull Long productId, @Min(1) int quantity) { }
}

public record OrderDto(Long id, String status, BigDecimal total) {
    static OrderDto from(Order o) {
        return new OrderDto(o.getId(), o.getStatus().name(), o.getTotal());
    }
}
```

```java
@Service
public class OrderService {

    private final OrderRepository orders;
    private final ProductService products;   // gọi qua service của module catalog
    private final JobService jobs;           // xem mục 1.8

    public OrderService(OrderRepository orders, ProductService products, JobService jobs) {
        this.orders = orders;
        this.products = products;
        this.jobs = jobs;
    }

    @Transactional
    public OrderDto placeOrder(Long userId, PlaceOrderRequest request) {
        Order order = new Order(userId);
        for (PlaceOrderRequest.Line line : request.lines()) {
            order.addItem(products.getEntity(line.productId()), line.quantity());
        }
        orders.save(order);

        // Ghi job gửi email trong CÙNG transaction với đơn hàng:
        // đơn lưu thành công thì job chắc chắn tồn tại, và ngược lại.
        jobs.enqueue("ORDER_CONFIRMATION_EMAIL", new OrderConfirmationPayload(order.getId(), userId));

        return OrderDto.from(order);
    }
}
```

```java
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderDto place(@AuthenticationPrincipal Jwt jwt,
                          @Valid @RequestBody PlaceOrderRequest request) {
        // Ở giai đoạn 1 có thể dùng session login thông thường;
        // mục 2.3 chuyển sang JWT để app stateless.
        return orderService.placeOrder(Long.valueOf(jwt.getSubject()), request);
    }
}
```

### 1.6. Xử lý lỗi thống nhất

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    public ProblemDetail notFound(EntityNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail invalid(MethodArgumentNotValidException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setDetail("Dữ liệu không hợp lệ");
        pd.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .toList());
        return pd;
    }
}
```

`ProblemDetail` theo chuẩn RFC 9457, giúp client nhận lỗi ở một định dạng thống nhất. Các exception không bắt ở đây sẽ được Sentry ghi nhận tự động.

### 1.7. Giám sát và backup từ ngày đầu

- **Health check:** Actuator cung cấp `/actuator/health`. Trỏ một dịch vụ uptime check (UptimeRobot, Better Stack…) vào đó để được báo khi app chết.
- **Error tracking:** Sentry gom exception kèm stack trace và request liên quan, thay vì phải đọc log trên server.
- **Log:** in log ra stdout; Spring Boot 3.4 trở lên hỗ trợ log dạng JSON qua `logging.structured.format.console: ecs`, thuận tiện khi đưa vào hệ thống log tập trung về sau.
- **Backup:** việc này nằm ngoài Spring. Với managed database, bật automated backup và point-in-time recovery. Nếu tự chạy PostgreSQL, dùng `pg_dump` theo lịch hoặc pgBackRest, đẩy bản backup sang storage khác máy. Mỗi tháng thử restore ít nhất một lần.

### 1.8. Background job bền vững bằng bảng `jobs` và `SKIP LOCKED`

Gửi email, tạo PDF hay gọi API bên thứ ba không nên chạy trong request của người dùng. Cách nhanh nhất là `@Async`, nhưng job chỉ nằm trong RAM: app khởi động lại là mất. Cách bền vững mà không cần thêm hạ tầng là lưu job vào chính PostgreSQL rồi cho một worker định kỳ lấy ra xử lý.

```sql
-- V2__jobs.sql
CREATE TABLE jobs (
    id          BIGSERIAL PRIMARY KEY,
    type        VARCHAR(100) NOT NULL,
    payload     TEXT         NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    attempts    INT          NOT NULL DEFAULT 0,
    run_at      TIMESTAMPTZ  NOT NULL,
    last_error  TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- partial index: chỉ đánh index các job còn chờ, giữ index nhỏ
CREATE INDEX idx_jobs_pending ON jobs (run_at) WHERE status = 'PENDING';
```

```java
@Entity
@Table(name = "jobs")
public class Job {

    private static final int MAX_ATTEMPTS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String type;
    private String payload;

    @Enumerated(EnumType.STRING)
    private JobStatus status = JobStatus.PENDING;

    private int attempts;
    private Instant runAt = Instant.now();
    private String lastError;

    protected Job() { }

    public Job(String type, String payload) {
        this.type = type;
        this.payload = payload;
    }

    public void markDone() {
        status = JobStatus.DONE;
    }

    public void markFailed(String error) {
        attempts++;
        lastError = error;
        if (attempts >= MAX_ATTEMPTS) {
            status = JobStatus.FAILED;          // cần người xem xét
        } else {
            // exponential backoff: 1, 2, 4, 8 phút
            runAt = Instant.now().plus(Duration.ofMinutes(1L << (attempts - 1)));
        }
    }

    public String getType() { return type; }
    public String getPayload() { return payload; }
}

public enum JobStatus { PENDING, DONE, FAILED }
```

```java
public interface JobRepository extends JpaRepository<Job, Long> {

    // FOR UPDATE SKIP LOCKED: mỗi worker khóa một lô job khác nhau,
    // nên chạy nhiều instance app (giai đoạn 2) cũng không xử lý trùng.
    @Query(value = """
            SELECT * FROM jobs
            WHERE status = 'PENDING' AND run_at <= now()
            ORDER BY run_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Job> lockNextBatch(@Param("limit") int limit);
}
```

```java
public interface JobHandler {
    String type();
    void handle(String payload) throws Exception;
}

@Service
public class JobService {

    private final JobRepository jobs;
    private final ObjectMapper mapper;

    public JobService(JobRepository jobs, ObjectMapper mapper) {
        this.jobs = jobs;
        this.mapper = mapper;
    }

    // Không có @Transactional riêng: tham gia transaction của nơi gọi
    public void enqueue(String type, Object payload) {
        try {
            jobs.save(new Job(type, mapper.writeValueAsString(payload)));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Payload không serialize được", e);
        }
    }
}
```

```java
@Component
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JobRepository jobs;
    private final Map<String, JobHandler> handlers;

    public JobWorker(JobRepository jobs, List<JobHandler> handlerList) {
        this.jobs = jobs;
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(JobHandler::type, h -> h));
    }

    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void poll() {
        for (Job job : jobs.lockNextBatch(10)) {
            try {
                JobHandler handler = handlers.get(job.getType());
                if (handler == null) {
                    throw new IllegalStateException("Không có handler cho " + job.getType());
                }
                handler.handle(job.getPayload());
                job.markDone();
            } catch (Exception e) {
                log.warn("Job {} lỗi", job.getType(), e);
                job.markFailed(e.getMessage());
            }
        }
        // các thay đổi trạng thái được flush khi transaction commit
    }
}
```

```java
@Component
public class OrderConfirmationEmailHandler implements JobHandler {

    private final JavaMailSender mail;
    private final ObjectMapper mapper;
    private final UserService users;

    public OrderConfirmationEmailHandler(JavaMailSender mail, ObjectMapper mapper, UserService users) {
        this.mail = mail;
        this.mapper = mapper;
        this.users = users;
    }

    @Override
    public String type() {
        return "ORDER_CONFIRMATION_EMAIL";
    }

    @Override
    public void handle(String payload) throws Exception {
        var p = mapper.readValue(payload, OrderConfirmationPayload.class);
        var msg = new SimpleMailMessage();
        msg.setTo(users.getEmail(p.userId()));
        msg.setSubject("Xác nhận đơn hàng #" + p.orderId());
        msg.setText("Cảm ơn bạn đã đặt hàng!");
        mail.send(msg);
    }
}

public record OrderConfirmationPayload(Long orderId, Long userId) { }
```

Nhớ thêm `@EnableScheduling` vào lớp `ShopApplication`. Vài lưu ý khi dùng mô hình này:

- Batch nhỏ (10 job) vì lô job bị khóa suốt thời gian xử lý. Handler chạy lâu (vài chục giây) nên đổi sang cách đánh dấu `RUNNING`, commit, rồi mới xử lý.
- Job có thể chạy lại khi lỗi, nên handler phải chịu được việc chạy hai lần. Với email, đôi khi khách nhận trùng là chấp nhận được; với thanh toán thì không.
- Định kỳ dọn các job `DONE` cũ để bảng không phình to.
- Nếu không muốn tự viết, các thư viện như JobRunr hay db-scheduler cung cấp cùng mô hình này kèm dashboard. Bảng `jobs` này cũng chính là tiền thân của outbox ở mục 3.4.

## Giai đoạn 2 — Khoảng 10K người dùng: tối ưu database, stateless, cache

Điểm nghẽn đầu tiên gần như luôn nằm ở database, vì vậy hãy tối ưu truy vấn trước khi thêm máy. Thứ tự hợp lý: query và index → connection pool → nhiều instance app → cache.

### 2.1. Tìm và sửa truy vấn chậm

Bật log truy vấn chậm trên PostgreSQL (`log_min_duration_statement = 200`, đơn vị ms), hoặc dùng extension `pg_stat_statements` để xem truy vấn nào tốn tổng thời gian nhiều nhất. Phía Spring, có thể tạm bật thống kê Hibernate khi debug:

```yaml
spring:
  jpa:
    properties:
      hibernate:
        generate_statistics: true      # chỉ bật khi điều tra, không để ở production
        default_batch_fetch_size: 50   # gom lazy loading thành truy vấn IN (...)
```

Với truy vấn tìm được, chạy `EXPLAIN ANALYZE` để xem PostgreSQL có đang quét toàn bảng (Seq Scan) không, rồi thêm index qua migration:

```sql
-- V3__indexes.sql
-- "Đơn hàng của tôi, mới nhất trước": index kép khớp cả WHERE lẫn ORDER BY
CREATE INDEX idx_orders_user_created ON orders (user_id, created_at DESC);

-- foreign key KHÔNG tự có index trong PostgreSQL
CREATE INDEX idx_order_items_order ON order_items (order_id);

CREATE INDEX idx_products_category ON products (category_id);
```

Với bảng đã lớn, dùng `CREATE INDEX CONCURRENTLY` để không khóa ghi; câu lệnh này không chạy được trong transaction, nên migration đó cần cấu hình Flyway riêng (ví dụ file `.conf` đi kèm với `executeInTransaction=false`). Đừng thêm index tràn lan: mỗi index làm chậm thao tác ghi và tốn dung lượng.

### 2.2. Sửa lỗi N+1 query

Lỗi kinh điển của JPA: lấy 20 đơn hàng bằng 1 query, rồi mỗi lần truy cập `order.getItems()` lại sinh thêm 1 query, tổng cộng 21 query. Cách sửa là nạp sẵn quan hệ cần dùng:

```java
public interface OrderRepository extends JpaRepository<Order, Long> {

    // Một query JOIN lấy luôn items
    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsById(Long id);

    // Danh sách có phân trang: KHÔNG fetch join collection (Hibernate sẽ
    // phân trang trong RAM). Lấy trang đơn hàng trước, items được gom
    // bằng default_batch_fetch_size ở trên.
    Page<Order> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
}
```

Cho màn hình chỉ cần vài cột, dùng projection thay vì nạp cả entity:

```java
public record OrderSummary(Long id, String status, BigDecimal total, Instant createdAt) { }

@Query("""
       select new com.example.shop.order.OrderSummary(o.id, cast(o.status as string), o.total, o.createdAt)
       from Order o where o.userId = :userId order by o.createdAt desc
       """)
List<OrderSummary> findSummaries(@Param("userId") Long userId, Pageable pageable);
```

### 2.3. Connection pool (HikariCP) và tách database

Nếu đang chạy PostgreSQL chung máy, đây là lúc chuyển sang máy riêng hoặc managed database. Spring Boot dùng HikariCP mặc định; cấu hình hợp lý:

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20        # mỗi instance app
      minimum-idle: 5
      connection-timeout: 3000     # ms: chờ lấy kết nối tối đa 3s rồi báo lỗi, đừng treo
      max-lifetime: 1800000        # 30 phút, nên ngắn hơn timeout phía DB/proxy
```

Pool lớn không có nghĩa là nhanh hơn. PostgreSQL xử lý hiệu quả nhất khi số kết nối đang hoạt động gần với số lõi CPU của nó. Phép tính quan trọng cần nhớ: số instance app × `maximum-pool-size` phải nhỏ hơn `max_connections` của database. Ví dụ 4 instance × 20 = 80 kết nối. Khi số instance tăng lên (nhất là khi autoscale ở giai đoạn 3), đặt PgBouncer ở chế độ transaction pooling giữa app và database.

### 2.4. App stateless với JWT

Để chạy nhiều instance sau load balancer, không instance nào được giữ trạng thái riêng: không session trong RAM, không file upload trên đĩa local. Với API, cách phổ biến là để một identity provider (Keycloak, Auth0, Cognito…) phát JWT, còn app chỉ kiểm tra chữ ký token.

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
```

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://auth.example.com/realms/shop   # Spring tự tải public key từ đây
```

```java
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
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));
        return http.build();
    }
}
```

Điểm yếu của JWT là khó thu hồi: token đã phát thì hợp lệ đến khi hết hạn. Vì vậy, đặt access token ngắn (5–15 phút) và dùng refresh token do identity provider quản lý; khi khóa tài khoản, chỉ cần thu hồi refresh token. Nếu ứng dụng render trang phía server và cần session truyền thống, dùng Spring Session với Redis (`spring-session-data-redis`) để mọi instance chia sẻ chung session.

File người dùng tải lên đưa thẳng lên object storage (S3, GCS, MinIO), trong database chỉ lưu key của file. Tốt hơn nữa là để client upload trực tiếp lên storage bằng presigned URL do backend cấp, giúp app server không phải trung chuyển dữ liệu nặng.

### 2.5. Load balancer và graceful shutdown

Chạy ít nhất 2 instance sau Nginx (hoặc load balancer của cloud). Lợi ích lớn nhất ở đây là hết điểm lỗi duy nhất ở tầng app, và có thể deploy từng instance mà không downtime.

```nginx
upstream shop_app {
    server app1:8080 max_fails=3 fail_timeout=10s;
    server app2:8080 max_fails=3 fail_timeout=10s;
}

server {
    listen 80;

    location / {
        proxy_pass http://shop_app;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_connect_timeout 2s;
        proxy_read_timeout    30s;
    }
}
```

```yaml
server:
  shutdown: graceful
  forward-headers-strategy: native   # tin header X-Forwarded-* từ proxy để biết IP thật, https

spring:
  lifecycle:
    timeout-per-shutdown-phase: 20s  # chờ tối đa 20s cho request đang chạy
```

Khi có nhiều instance, các `@Scheduled` task thông thường (ví dụ gửi báo cáo hằng ngày) sẽ chạy trên mọi instance cùng lúc. Worker ở mục 1.8 đã an toàn nhờ `SKIP LOCKED`; với các task khác, dùng thư viện ShedLock để đảm bảo mỗi lần chỉ một instance chạy.

### 2.6. Cache với Redis

Chỉ thêm cache khi số đo cho thấy database quá tải vì đọc, và dữ liệu đó được đọc nhiều hơn ghi rất nhiều (danh mục, chi tiết sản phẩm, cấu hình). Spring Cache cho phép thêm cache bằng annotation mà không phải viết code Redis trực tiếp.

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-cache</artifactId>
</dependency>
```

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: 6379
      timeout: 500ms        # Redis chậm thì bỏ qua nhanh, đừng kéo cả request theo
  cache:
    type: redis
    redis:
      time-to-live: 10m     # TTL mặc định: lưới an toàn nếu quên evict
```

```java
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    RedisCacheManagerBuilderCustomizer cacheCustomizer() {
        return builder -> builder
            // Chỉ ghi/xóa cache sau khi transaction commit thành công
            .transactionAware()
            .enableStatistics()   // bật để có metric hit/miss
            .withCacheConfiguration("products",
                RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofMinutes(10)))
            .withCacheConfiguration("categories",
                RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofHours(1)));
    }
}
```

```java
// Cache DTO, KHÔNG cache entity JPA (entity chứa proxy lazy-loading, serialize lỗi)
public record ProductDto(Long id, String name, BigDecimal price, Long categoryId)
        implements Serializable { }

@Service
public class ProductService {

    private final ProductRepository products;

    public ProductService(ProductRepository products) {
        this.products = products;
    }

    // sync = true: khi cache miss, chỉ một luồng trong instance truy vấn DB,
    // các luồng khác chờ kết quả -> giảm cache stampede
    @Cacheable(cacheNames = "products", key = "#id", sync = true)
    @Transactional(readOnly = true)
    public ProductDto getProduct(Long id) {
        return products.findById(id)
            .map(p -> new ProductDto(p.getId(), p.getName(), p.getPrice(), p.getCategoryId()))
            .orElseThrow(() -> new EntityNotFoundException("Không tìm thấy sản phẩm " + id));
    }

    @CacheEvict(cacheNames = "products", key = "#id")
    @Transactional
    public void updatePrice(Long id, BigDecimal newPrice) {
        Product p = products.findById(id).orElseThrow();
        p.changePrice(newPrice);
    }
}
```

Ba điểm dễ sai với cache:

- **Thời điểm xóa cache.** Nếu cache bị xóa trước khi transaction commit, một request khác có thể đọc dữ liệu cũ từ DB và nạp lại vào cache, khiến dữ liệu sai tồn tại đến hết TTL. `transactionAware()` ở trên dời việc xóa cache đến sau khi commit.
- **Serialization.** Cấu hình trên dùng cơ chế serialize mặc định của Java, nên DTO phải `implements Serializable`. Khi đổi cấu trúc DTO, đổi luôn tên cache (ví dụ `products-v2`) để không đọc nhầm dữ liệu kiểu cũ.
- **Stampede.** `sync = true` chỉ chặn trong phạm vi một instance. Với key cực nóng trên nhiều instance, có thể thêm khóa phân tán hoặc làm mới cache chủ động trước khi hết hạn.

Cuối cùng, đo hiệu quả: Actuator cung cấp metric `cache.gets` theo kết quả hit/miss. Tỉ lệ hit thấp nghĩa là cache đang tốn RAM mà không giúp được gì.

## Giai đoạn 3 — Khoảng 1M người dùng: phân tán có chọn lọc

Đi theo thứ tự từ rẻ đến đắt và dừng ở bước đã giải quyết được điểm nghẽn: read replica → hàng đợi tin nhắn → partitioning → (hiếm khi) sharding. Song song đó, hệ thống cần tự bảo vệ khi quá tải và cần quan sát chi tiết hơn.

### 3.1. Tách đọc/ghi với read replica

Primary nhận mọi thao tác ghi, các replica nhận thao tác đọc. Trong Spring, cách gọn nhất là định tuyến theo transaction: `@Transactional(readOnly = true)` đi replica, còn lại đi primary.

```yaml
app:
  datasource:
    primary:
      jdbc-url: jdbc:postgresql://db-primary:5432/shop
      username: ${DB_USER}
      password: ${DB_PASSWORD}
      maximum-pool-size: 20
    replica:
      jdbc-url: jdbc:postgresql://db-replica:5432/shop   # endpoint đọc, có thể trỏ tới nhiều replica
      username: ${DB_USER}
      password: ${DB_PASSWORD}
      maximum-pool-size: 30
      read-only: true
```

```java
public class ReadWriteRoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        return TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ? "replica"
                : "primary";
    }
}
```

```java
@Configuration
public class DataSourceConfig {

    @Bean
    @ConfigurationProperties("app.datasource.primary")
    HikariDataSource primaryDataSource() {
        return new HikariDataSource();
    }

    @Bean
    @ConfigurationProperties("app.datasource.replica")
    HikariDataSource replicaDataSource() {
        return new HikariDataSource();
    }

    @Bean
    @Primary
    DataSource dataSource(HikariDataSource primaryDataSource,
                          HikariDataSource replicaDataSource) {
        var routing = new ReadWriteRoutingDataSource();
        routing.setTargetDataSources(Map.of(
                "primary", primaryDataSource,
                "replica", replicaDataSource));
        routing.setDefaultTargetDataSource(primaryDataSource);
        routing.afterPropertiesSet();

        // Lazy: chỉ lấy kết nối thật khi câu SQL đầu tiên chạy, tức là SAU KHI
        // Spring đã đánh dấu transaction readOnly. Thiếu lớp này, routing luôn ra primary.
        return new LazyConnectionDataSourceProxy(routing);
    }
}
```

Khi tự khai báo `DataSource`, Spring Boot không tự cấu hình datasource nữa; Flyway và JPA sẽ dùng bean `@Primary` này. Migration chạy ngoài transaction read-only nên luôn đi primary.

```java
@Transactional(readOnly = true)          // -> replica
public Page<OrderSummary> myOrders(Long userId, Pageable page) { ... }

@Transactional                           // -> primary
public OrderDto placeOrder(Long userId, PlaceOrderRequest req) { ... }
```

**Replication lag.** Replica luôn chậm hơn primary một chút (thường vài chục ms, có lúc vài giây). Người dùng vừa đặt hàng xong mà mở ngay "Đơn hàng của tôi" có thể không thấy đơn mới. Các cách xử lý thường dùng:

- Màn hình ngay sau thao tác ghi (trang xác nhận đơn) đọc từ primary bằng `@Transactional` thường, hoặc trả luôn dữ liệu vừa ghi trong response.
- Ghi lại thời điểm ghi gần nhất của user (ví dụ trong Redis, sống vài giây) và chuyển các lần đọc của user đó sang primary trong khoảng thời gian ấy.
- Theo dõi độ trễ replica (`pg_stat_replication` trên primary) và cảnh báo khi vượt ngưỡng.

**PgBouncer.** Khi số instance tăng, đặt PgBouncer (transaction pooling) trước database. Với chế độ này, kiểm tra hỗ trợ prepared statement: PgBouncer bản mới hỗ trợ qua `max_prepared_statements`; với bản cũ, thêm `prepareThreshold=0` vào JDBC URL.

### 3.2. Partitioning cho bảng rất lớn

Bảng tăng liên tục theo thời gian (đơn hàng, sự kiện, log) có thể chia partition ngay trong một PostgreSQL. Truy vấn có điều kiện thời gian chỉ quét partition liên quan, và xóa dữ liệu cũ chỉ cần `DROP` một partition thay vì `DELETE` hàng triệu dòng.

```sql
-- V10__events_partitioned.sql
CREATE TABLE user_events (
    id          BIGSERIAL,
    user_id     BIGINT      NOT NULL,
    event_type  VARCHAR(50) NOT NULL,
    payload     JSONB,
    created_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (id, created_at)          -- khóa chính phải chứa cột partition
) PARTITION BY RANGE (created_at);

CREATE TABLE user_events_2026_10 PARTITION OF user_events
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE user_events_2026_11 PARTITION OF user_events
    FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');

CREATE INDEX idx_user_events_user ON user_events (user_id, created_at);
```

Partition cho tháng mới phải được tạo trước; dùng extension `pg_partman` hoặc một job hằng tháng thay vì nhớ làm tay. Bảng chỉ thêm dữ liệu như thế này thường được ghi bằng `JdbcTemplate` cho đơn giản, thay vì map entity JPA với khóa chính kép.

### 3.3. Sharding: phương án cuối cùng

Sharding chia dữ liệu ra nhiều cụm database theo một khóa (thường là `user_id` hoặc `tenant_id`). Chỉ cân nhắc khi primary mạnh nhất có thể thuê vẫn không chịu nổi lượng ghi hoặc dung lượng, vì sharding khiến JOIN, transaction và migration xuyên shard trở nên rất khó, và gần như không thể quay lại.

Nếu buộc phải làm, ưu tiên giải pháp ở tầng database như Citus (PostgreSQL) để code ứng dụng thay đổi ít nhất. Tự sharding trong Spring về nguyên tắc là mở rộng `AbstractRoutingDataSource` ở trên với khóa định tuyến là shard tính từ `user_id`, nhưng kéo theo rất nhiều việc: di chuyển dữ liệu khi thêm shard, truy vấn tổng hợp, ID duy nhất toàn cục.

### 3.4. Hàng đợi tin nhắn: transactional outbox và RabbitMQ

Khi nhiều phần của hệ thống cần phản ứng với cùng một sự kiện (đơn hàng mới thì gửi email, trừ kho, cập nhật search index, ghi thống kê), bảng `jobs` không còn phù hợp. Lúc này cần một message broker. RabbitMQ đủ cho hầu hết trường hợp; chỉ chọn Kafka khi cần lưu luồng sự kiện để phát lại, nhiều hệ thống cùng đọc độc lập, hoặc throughput rất cao.

**Vấn đề dual-write.** Nếu code lưu đơn hàng vào DB rồi gửi message lên RabbitMQ, có thể xảy ra trường hợp DB commit thành công nhưng gửi message thất bại (hoặc ngược lại), khiến hai bên lệch nhau. Transactional outbox giải quyết bằng cách ghi sự kiện vào bảng `outbox` trong cùng transaction với dữ liệu nghiệp vụ, sau đó một tiến trình riêng đọc bảng và đẩy lên broker.

```sql
-- V11__outbox.sql
CREATE TABLE outbox_events (
    id            UUID PRIMARY KEY,
    routing_key   VARCHAR(100) NOT NULL,
    payload       TEXT         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;

CREATE TABLE processed_messages (
    id            UUID PRIMARY KEY,
    processed_at  TIMESTAMPTZ NOT NULL
);
```

```java
// Trong OrderService.placeOrder(...), cùng transaction với orders.save(order):
outbox.save(OutboxEvent.of("order.created",
        mapper.writeValueAsString(new OrderCreated(order.getId(), userId, order.getTotal()))));

public record OrderCreated(Long orderId, Long userId, BigDecimal total) { }
```

```java
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    private UUID id;
    private String routingKey;
    private String payload;
    private Instant createdAt;
    private Instant publishedAt;

    protected OutboxEvent() { }

    public static OutboxEvent of(String routingKey, String payload) {
        var e = new OutboxEvent();
        e.id = UUID.randomUUID();       // cũng là message id để consumer chống trùng
        e.routingKey = routingKey;
        e.payload = payload;
        e.createdAt = Instant.now();
        return e;
    }

    public void markPublished() { publishedAt = Instant.now(); }
    public UUID getId() { return id; }
    public String getRoutingKey() { return routingKey; }
    public String getPayload() { return payload; }
}

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published_at IS NULL
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockUnpublished(@Param("limit") int limit);
}
```

**Khai báo exchange, queue và dead-letter queue:**

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
```

```java
@Configuration
public class MessagingConfig {

    public static final String EVENTS_EXCHANGE = "shop.events";
    public static final String DLX = "shop.dlx";
    public static final String EMAIL_QUEUE = "email.order-created";

    @Bean
    TopicExchange eventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE);
    }

    @Bean
    DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX);
    }

    @Bean
    Queue emailQueue() {
        return QueueBuilder.durable(EMAIL_QUEUE)
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(EMAIL_QUEUE + ".dlq")
                .build();
    }

    @Bean
    Queue emailDeadLetterQueue() {
        return QueueBuilder.durable(EMAIL_QUEUE + ".dlq").build();
    }

    @Bean
    Binding emailBinding() {
        return BindingBuilder.bind(emailQueue()).to(eventsExchange()).with("order.created");
    }

    @Bean
    Binding emailDlqBinding() {
        return BindingBuilder.bind(emailDeadLetterQueue()).to(deadLetterExchange())
                .with(EMAIL_QUEUE + ".dlq");
    }
}
```

**Relay: đọc outbox và đẩy lên RabbitMQ.**

```java
@Component
public class OutboxRelay {

    private final OutboxRepository outbox;
    private final RabbitTemplate rabbit;

    public OutboxRelay(OutboxRepository outbox, RabbitTemplate rabbit) {
        this.outbox = outbox;
        this.rabbit = rabbit;
    }

    @Scheduled(fixedDelay = 500)
    @Transactional
    public void publish() {
        for (OutboxEvent e : outbox.lockUnpublished(100)) {
            MessageProperties props = new MessageProperties();
            props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            props.setMessageId(e.getId().toString());
            props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);

            rabbit.send(MessagingConfig.EVENTS_EXCHANGE, e.getRoutingKey(),
                    new Message(e.getPayload().getBytes(StandardCharsets.UTF_8), props));
            e.markPublished();
        }
        // Gửi lỗi -> exception -> rollback -> sự kiện vẫn chưa published, lần sau gửi lại.
    }
}
```

Mô hình này đảm bảo "ít nhất một lần": sự kiện không bao giờ mất, nhưng có thể được gửi hai lần (ví dụ gửi thành công nhưng app chết trước khi commit). Để tin chắc broker đã nhận, bật publisher confirms (`spring.rabbitmq.publisher-confirm-type: correlated`). Khi đó, gửi kèm CorrelationData và chờ broker xác nhận rồi mới đánh dấu published. Nếu không muốn tự viết relay, Debezium có thể đọc thay đổi của bảng outbox từ WAL của PostgreSQL và đẩy lên broker.

**Consumer idempotent.** Vì message có thể đến hai lần, consumer phải ghi nhận message đã xử lý:

```java
@Entity
@Table(name = "processed_messages")
public class ProcessedMessage {
    @Id
    private UUID id;
    private Instant processedAt;
    protected ProcessedMessage() { }
}

public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, UUID> {

    // 1 = lần đầu thấy message này, 0 = đã xử lý rồi
    @Modifying
    @Query(value = """
            INSERT INTO processed_messages (id, processed_at)
            VALUES (:id, now())
            ON CONFLICT (id) DO NOTHING
            """, nativeQuery = true)
    int markProcessed(@Param("id") UUID id);
}
```

```java
@Component
public class OrderCreatedEmailListener {

    private final ProcessedMessageRepository processed;
    private final OrderEmailService emails;
    private final ObjectMapper mapper;

    public OrderCreatedEmailListener(ProcessedMessageRepository processed,
                                     OrderEmailService emails, ObjectMapper mapper) {
        this.processed = processed;
        this.emails = emails;
        this.mapper = mapper;
    }

    @RabbitListener(queues = MessagingConfig.EMAIL_QUEUE)
    @Transactional
    public void onOrderCreated(Message message) throws IOException {
        UUID messageId = UUID.fromString(message.getMessageProperties().getMessageId());
        if (processed.markProcessed(messageId) == 0) {
            return;   // trùng lặp, bỏ qua
        }
        OrderCreated event = mapper.readValue(message.getBody(), OrderCreated.class);
        emails.sendConfirmation(event.orderId(), event.userId());
        // Nếu gửi email lỗi -> exception -> rollback, bản ghi processed bị hủy,
        // message được thử lại.
    }
}
```

```yaml
spring:
  rabbitmq:
    host: ${RABBIT_HOST:localhost}
    publisher-confirm-type: correlated
    listener:
      simple:
        concurrency: 2
        max-concurrency: 10
        prefetch: 20
        default-requeue-rejected: false   # lỗi hết lượt retry -> sang DLQ, không lặp vô hạn
        retry:
          enabled: true
          max-attempts: 3
          initial-interval: 1s
          multiplier: 2
```

Cấu hình retry chạy ngay trong consumer; hết 3 lần mà vẫn lỗi thì message vào `email.order-created.dlq`. Hãy cảnh báo khi DLQ có message và có công cụ để đẩy lại sau khi sửa lỗi. Lưu ý: tác động ra bên ngoài như gửi email không thể rollback, nên trong trường hợp hiếm (email đã gửi nhưng commit lỗi), khách có thể nhận hai email. Với các tác động nghiêm trọng như thanh toán, hãy truyền idempotency key sang dịch vụ bên ngoài (xem mục 3.5).

### 3.5. Tự bảo vệ: timeout, retry, circuit breaker

Ở quy mô lớn, sự cố thường không đến từ code của bạn mà từ một phụ thuộc chậm: cổng thanh toán, API đối tác. Không có timeout, request chờ mãi, thread và kết nối DB bị giữ, rồi cả hệ thống nghẽn theo. Ba lớp bảo vệ cần có, kể cả khi bạn vẫn là monolith:

**Timeout cho mọi lời gọi ra ngoài.**

```java
@Configuration
public class HttpClientConfig {

    @Bean
    RestClient paymentClient(RestClient.Builder builder) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return builder
                .baseUrl("https://api.payment-provider.example")
                .requestFactory(factory)
                .build();
    }
}
```

**Retry và circuit breaker với Resilience4j.**

```xml
<dependency>
  <groupId>io.github.resilience4j</groupId>
  <artifactId>resilience4j-spring-boot3</artifactId>
  <version>${resilience4j.version}</version>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
```

```yaml
resilience4j:
  circuitbreaker:
    instances:
      payment:
        sliding-window-size: 20
        minimum-number-of-calls: 10
        failure-rate-threshold: 50          # >= 50% lỗi trong 20 lần gọi gần nhất -> mở mạch
        slow-call-duration-threshold: 3s
        slow-call-rate-threshold: 50        # quá chậm cũng tính là lỗi
        wait-duration-in-open-state: 30s    # 30s sau thử lại vài request
        permitted-number-of-calls-in-half-open-state: 3
  retry:
    instances:
      payment:
        max-attempts: 3
        wait-duration: 200ms
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2
        retry-exceptions:
          - org.springframework.web.client.ResourceAccessException   # lỗi mạng, timeout
```

```java
@Service
public class PaymentGateway {

    private final RestClient client;

    public PaymentGateway(RestClient paymentClient) {
        this.client = paymentClient;
    }

    // Mặc định Retry bọc ngoài CircuitBreaker. Đặt fallback ở Retry (lớp ngoài);
    // nếu đặt ở CircuitBreaker, lỗi đầu tiên đã trả fallback và Retry không bao giờ chạy.
    @CircuitBreaker(name = "payment")
    @Retry(name = "payment", fallbackMethod = "paymentUnavailable")
    public PaymentResult charge(ChargeRequest request) {
        return client.post()
                .uri("/charges")
                // Bắt buộc khi có retry: nhà cung cấp dùng key này để không trừ tiền hai lần
                .header("Idempotency-Key", "order-" + request.orderId())
                .body(request)
                .retrieve()
                .body(PaymentResult.class);
    }

    // Cùng tham số + Throwable. Mạch mở: không gọi nữa, đánh dấu chờ xử lý sau
    PaymentResult paymentUnavailable(ChargeRequest request, Throwable cause) {
        return PaymentResult.pending(request.orderId());
    }
}
```

Chỉ retry khi lỗi có tính tạm thời (mạng, timeout, 503) và thao tác an toàn khi lặp lại. Không retry lỗi 4xx: gửi lại một request sai vẫn sai. Circuit breaker giúp hệ thống "thất bại nhanh" thay vì chờ timeout hàng loạt khi phụ thuộc đã chết. Trạng thái của mạch có trong Actuator (`/actuator/health`, `/actuator/circuitbreakers`) để đưa lên dashboard.

**Graceful degradation.** Với tính năng phụ, fallback trả về giá trị an toàn thay vì lỗi: gợi ý sản phẩm lỗi thì trả danh sách rỗng, trang sản phẩm vẫn hiển thị bình thường.

### 3.6. Rate limiting bằng Redis

Giới hạn số request mỗi user hoặc mỗi IP để chống lạm dụng và để một khách hàng không làm chậm tất cả người khác. Có thể đặt ở API gateway hoặc Nginx (`limit_req`); nếu cần giới hạn theo user đã đăng nhập, làm trong app với Redis để mọi instance dùng chung bộ đếm:

```java
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int LIMIT_PER_MINUTE = 120;

    private final StringRedisTemplate redis;

    public RateLimitFilter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Filter này chạy sau Spring Security nên đã biết user là ai
        String client = request.getUserPrincipal() != null
                ? "u:" + request.getUserPrincipal().getName()
                : "ip:" + request.getRemoteAddr();
        long window = Instant.now().getEpochSecond() / 60;
        String key = "rl:" + client + ":" + window;

        Long count;
        try {
            count = redis.opsForValue().increment(key);
            if (count != null && count == 1) {
                redis.expire(key, Duration.ofSeconds(90));
            }
        } catch (DataAccessException e) {
            chain.doFilter(request, response);   // Redis lỗi: cho qua, đừng chặn toàn bộ người dùng
            return;
        }

        if (count != null && count > LIMIT_PER_MINUTE) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "60");
            return;
        }
        chain.doFilter(request, response);
    }
}
```

Đây là thuật toán fixed window đơn giản: dễ hiểu, đủ cho đa số trường hợp, nhưng cho phép bùng nổ gấp đôi tại ranh giới giữa hai phút. Khi cần chính xác hơn (token bucket, nhiều mức giới hạn theo gói dịch vụ), dùng thư viện Bucket4j với backend Redis. Nhờ `forward-headers-strategy` ở mục 2.5, `getRemoteAddr()` trả về IP thật của client thay vì IP của Nginx.

### 3.7. Observability: metrics, tracing, log

Giám sát cơ bản từ giai đoạn 1 giờ được nâng lên ba trụ cột: metrics (hệ thống đang thế nào), tracing (một request đi qua những đâu, chậm ở bước nào) và log có cấu trúc gắn với trace.

```xml
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
  <groupId>io.opentelemetry</groupId>
  <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      probes:
        enabled: true                 # /actuator/health/liveness và /readiness cho Kubernetes
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true    # để Prometheus tính được p95, p99
  tracing:
    sampling:
      probability: 0.1                # lấy mẫu 10% request
  otlp:
    tracing:
      endpoint: http://otel-collector:4318/v1/traces

logging:
  structured:
    format:
      console: ecs                    # log JSON, tự kèm traceId/spanId
```

Với cấu hình này, Spring tự đo HTTP request, truy vấn JDBC qua Hikari, cache, RabbitMQ listener, và truyền trace id qua `RestClient` lẫn message RabbitMQ (Spring AMQP cần bật `observation-enabled` trên template và listener). Metric nghiệp vụ tự thêm bằng `MeterRegistry`:

```java
@Component
public class OrderMetrics {

    private final Counter ordersPlaced;

    public OrderMetrics(MeterRegistry registry) {
        this.ordersPlaced = Counter.builder("shop.orders.placed")
                .description("Số đơn hàng được tạo")
                .register(registry);
    }

    public void orderPlaced() {
        ordersPlaced.increment();
    }
}
```

Prometheus đọc `/actuator/prometheus`, Grafana vẽ dashboard, trace đi qua OpenTelemetry Collector tới Tempo hoặc Jaeger. Thay vì cảnh báo theo CPU, hãy cảnh báo theo SLO mà người dùng cảm nhận, ví dụ: p95 của `/api/orders` vượt 300ms trong 5 phút, tỉ lệ lỗi 5xx vượt 1%, số message trong DLQ lớn hơn 0, hay pool Hikari phải chờ kết nối (`hikaricp.connections.pending` > 0).

### 3.8. Đóng gói và triển khai: Docker, Kubernetes, autoscaling

**Docker image.** Cách đơn giản nhất là để Spring Boot tự build image bằng Buildpacks: `./mvnw spring-boot:build-image`. Nếu tự viết Dockerfile, dùng multi-stage build:

```dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -q dependency:go-offline
COPY src src
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system app
USER app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
```

Tách bước tải dependency giúp Docker cache layer đó, chỉ build lại khi `pom.xml` đổi. `MaxRAMPercentage` để JVM tính heap theo giới hạn RAM của container thay vì RAM của máy chủ.

**Có cần Kubernetes không?** Chỉ khi có nhiều service, nhiều team và có người vận hành được nó. Trước đó, các nền tảng như AWS ECS, Google Cloud Run hay một PaaS cho phần lớn lợi ích (autoscaling, rolling deploy, health check) với công vận hành ít hơn nhiều. Nếu đã dùng Kubernetes:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: shop-api
spec:
  replicas: 3
  selector:
    matchLabels:
      app: shop-api
  template:
    metadata:
      labels:
        app: shop-api
    spec:
      terminationGracePeriodSeconds: 40   # lớn hơn timeout-per-shutdown-phase (20s)
      containers:
        - name: app
          image: registry.example.com/shop-api:1.4.2
          ports:
            - containerPort: 8080
          env:
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: shop-db
                  key: password
          resources:
            requests:
              cpu: 500m
              memory: 768Mi
            limits:
              memory: 1Gi
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            periodSeconds: 5
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: shop-api
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: shop-api
  minReplicas: 3
  maxReplicas: 12
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
```

Ba lưu ý khi autoscale ứng dụng Spring Boot:

- **Scale theo CPU, không theo memory.** JVM giữ heap đã cấp phát và ít khi trả lại, nên memory gần như không giảm khi tải giảm; HPA theo memory sẽ chỉ tăng mà không giảm.
- **Tính trước kết nối database.** `maxReplicas` × `maximum-pool-size` = 12 × 20 = 240 kết nối ở mức tối đa. Con số này phải nằm trong giới hạn của PgBouncer hoặc database, nếu không autoscale sẽ đánh sập DB đúng lúc tải cao nhất.
- **Readiness khác liveness.** Readiness lỗi thì pod chỉ bị rút khỏi load balancer; liveness lỗi thì pod bị khởi động lại. Đừng để liveness phụ thuộc vào database, nếu không DB chậm một chút sẽ khiến mọi pod bị restart cùng lúc. Mặc định của Spring Boot đã tách đúng như vậy.

## Tổng hợp: công cụ Spring theo từng giai đoạn

| Nhu cầu | Giai đoạn | Hiện thực trong Spring Boot | Khi nào thêm |
| --- | --- | --- | --- |
| Cấu trúc code | 1 | Package theo module, Spring Modulith (tùy chọn) | Ngày đầu |
| Schema database | 1 | Flyway, `ddl-auto: validate` | Ngày đầu |
| Health, error tracking | 1 | Actuator, Sentry | Ngày đầu |
| Việc chạy nền | 1 | Bảng `jobs` + `@Scheduled` + `SKIP LOCKED` | Ngày đầu |
| Truy vấn chậm, N+1 | 2 | `@EntityGraph`, projection, `default_batch_fetch_size`, index | Khi slow query log chỉ ra |
| Kết nối database | 2 | HikariCP, sau đó PgBouncer | Khi tách DB / nhiều instance |
| App stateless | 2 | OAuth2 Resource Server (JWT) hoặc Spring Session Redis | Trước khi chạy 2+ instance |
| Nhiều instance | 2 | Nginx/LB, graceful shutdown, ShedLock | Khi cần hết SPOF |
| Cache | 2 | Spring Cache + Redis, `transactionAware()` | Khi DB quá tải vì đọc |
| Tải đọc lớn | 3 | `AbstractRoutingDataSource` + `LazyConnectionDataSourceProxy` | Khi primary quá tải vì đọc |
| Sự kiện nhiều bên nhận | 3 | Outbox + Spring AMQP, consumer idempotent, DLQ | Khi bảng `jobs` không đủ |
| Phụ thuộc bên ngoài | 3 | `RestClient` timeout, Resilience4j | Khi có phụ thuộc quan trọng |
| Chống lạm dụng | 3 | Filter + Redis, hoặc Bucket4j | Khi có API công khai hoặc bị spam |
| Quan sát chi tiết | 3 | Micrometer, Prometheus, OpenTelemetry, log JSON | Khi có nhiều thành phần |
| Triển khai co giãn | 3 | Buildpacks/Docker, ECS/Cloud Run hoặc Kubernetes + HPA | Khi tải dao động lớn |
| Bảng rất lớn | 3 | Partition PostgreSQL + `pg_partman` | Khi bảng đến hàng trăm triệu dòng |
| Vượt sức một primary | 3 | Citus hoặc routing theo shard | Phương án cuối cùng |

## Kết luận

Spring Boot cho phép đi hết lộ trình này mà không phải đổi framework: monolith ở giai đoạn 1 vẫn là cùng một codebase ở giai đoạn 3, chỉ được bổ sung từng lớp khi số đo đòi hỏi. Hầu hết các bước chỉ là thêm một starter và vài dòng cấu hình, nên rất dễ bị cám dỗ thêm sớm. Hãy chống lại điều đó: mỗi thành phần mới là thêm một thứ có thể hỏng, cần giám sát và cần người hiểu nó.

Vòng lặp không đổi qua mọi giai đoạn: đo, tìm điểm nghẽn lớn nhất, chọn giải pháp đơn giản nhất xử lý được nó, rồi đo lại.
