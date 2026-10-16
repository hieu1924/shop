# Mục 3.8: multi-stage build bằng Gradle.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle gradle
# gradlew checkout trên Windows có thể mang CRLF, Linux sẽ báo "bad interpreter"
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
# Tải dependency trước: Docker cache layer này, chỉ chạy lại khi build.gradle đổi
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon -q bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system app
USER app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
# MaxRAMPercentage: JVM tính heap theo giới hạn RAM của container thay vì RAM của máy chủ
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
