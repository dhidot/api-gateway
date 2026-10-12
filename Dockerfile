# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw

COPY src/main/ src/main/

# Automated tests dijalankan oleh job CI sebelum image dibuild.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -Dmaven.test.skip=true package \
    && cp target/api-gateway-*.jar /build/app.jar

FROM eclipse-temurin:21-jre-noble AS runtime
LABEL org.opencontainers.image.title="CareerTrack API Gateway"
WORKDIR /app

RUN groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app \
       --no-create-home --shell /usr/sbin/nologin app

COPY --from=build --chown=10001:10001 /build/app.jar /app/app.jar

USER 10001:10001
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]