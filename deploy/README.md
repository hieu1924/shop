# deploy/

Các file mẫu cho phần triển khai ở giai đoạn 2-3. Ghi rõ cái nào đã được chạy thử:

| File | Trạng thái |
| --- | --- |
| `../Dockerfile` | **Đã chạy thử**: build image, chạy container với profile `prod`, `/actuator/health/readiness` UP, log JSON, chạy bằng user không phải root. |
| `nginx/shop.conf` | Chưa chạy thử. Chặn `/actuator/` ra ngoài. |
| `pgbouncer/pgbouncer.ini` | Chưa chạy thử. |
| `k8s/shop-api.yaml` | Chưa chạy thử trên cluster. |

Build và chạy thử image local:

    docker build -t shop-api:local .
    docker run --rm -p 8090:8080 -e DB_HOST=host.docker.internal -e DB_PORT=5435 -e DB_PASSWORD=shop \
        -e REDIS_HOST=host.docker.internal -e RABBIT_HOST=host.docker.internal -e SPRING_PROFILES_ACTIVE=prod \
        -e JWT_PUBLIC_KEY_LOCATION=classpath:jwt/dev-public.pem shop-api:local

Profile `prod` bắt buộc có `JWT_PUBLIC_KEY_LOCATION` (không có mặc định, thiếu là app không khởi động). Giá trị `classpath:jwt/dev-public.pem` ở
trên chỉ để chạy thử local: production phải trỏ tới public key của identity provider.

Nếu không muốn tự viết Dockerfile, Spring Boot build được image bằng Buildpacks: `./gradlew bootBuildImage`.
