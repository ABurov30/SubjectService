# syntax=docker/dockerfile:1.7

FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /app

COPY pom.xml lombok.config ./
COPY config config
COPY src src

RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn --batch-mode package \
    && cp target/subject-service-*.jar /app/app.jar

FROM eclipse-temurin:17-jre-jammy AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin --no-log-init app

WORKDIR /app
COPY --from=build --chown=10001:10001 /app/app.jar app.jar

USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=60s --retries=12 \
    CMD curl --fail --silent --show-error --max-time 2 http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
