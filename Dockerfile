# syntax=docker/dockerfile:1
#
# Two-stage build:
#   1. "build" stage: a full JDK + Maven image compiles the code and produces the Spring Boot jar.
#   2. runtime stage: a much smaller JRE-only image that contains just the jar.
# The final image therefore contains no compiler, no Maven and no source code.

# ---------- Stage 1: build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml .
COPY src ./src

# --mount=type=cache keeps ~/.m2 between builds, so dependencies are downloaded only once.
# Tests are skipped here because they need Docker themselves (Testcontainers); run them with ./mvnw test.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests \
    && cp target/syncsphere-*.jar /workspace/app.jar

# ---------- Stage 2: runtime ----------
FROM eclipse-temurin:21-jre-alpine

# Run as an unprivileged user instead of root.
RUN addgroup -S syncsphere && adduser -S syncsphere -G syncsphere
WORKDIR /app
COPY --from=build --chown=syncsphere:syncsphere /workspace/app.jar /app/app.jar
USER syncsphere

EXPOSE 8080

# Let the JVM size its heap from the container memory limit instead of the host's RAM.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

# exec form: java becomes PID 1 and receives SIGTERM directly, so graceful shutdown works.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
