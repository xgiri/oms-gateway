# ---- Build stage ------------------------------------------------------
# Use the project's own Maven wrapper (mvnw) rather than a Maven base image,
# so the build here always matches the version pinned in
# .mvn/wrapper/maven-wrapper.properties instead of whatever the CI/host has.
# Same pattern as oms-main's Dockerfile.
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /build

# Copy only what's needed to resolve dependencies first, so this layer is
# cached across builds unless pom.xml itself changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline

COPY src/ src/
RUN ./mvnw -B clean package -DskipTests

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
