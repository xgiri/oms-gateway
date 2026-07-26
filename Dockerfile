# ---- Build stage ------------------------------------------------------
# No mvnw wrapper checked into this module yet (add one with `mvn -N
# wrapper:wrapper` to match oms-main's pinned-Maven-version approach) — uses
# a Maven base image for now.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml ./
RUN mvn -B dependency:go-offline

COPY src/ src/
RUN mvn -B clean package -DskipTests

# ---- Runtime stage ------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system omsgw && useradd --system --gid omsgw omsgw
USER omsgw

COPY --from=build /build/target/*.jar app.jar

ARG GIT_SHA=unknown
ARG APP_VERSION=unknown
LABEL org.opencontainers.image.revision=$GIT_SHA
LABEL org.opencontainers.image.version=$APP_VERSION

EXPOSE 8090 8091

# Port 8091 — management.server.port in application.yml moves actuator
# (health included) off the main gateway port, same convention as oms-main.
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
    CMD curl --fail http://localhost:8091/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
