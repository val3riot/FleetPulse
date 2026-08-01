# syntax=docker/dockerfile:1.7

ARG MAVEN_IMAGE=maven:3.9.13-eclipse-temurin-21-alpine
ARG JAVA_RUNTIME_IMAGE=eclipse-temurin:21.0.11_10-jre-alpine-3.22

FROM ${MAVEN_IMAGE} AS build
WORKDIR /workspace

COPY pom.xml ./
COPY libraries/telemetry-contracts/pom.xml libraries/telemetry-contracts/pom.xml
COPY libraries/tcp-protocol/pom.xml libraries/tcp-protocol/pom.xml
COPY services/fleet-api/pom.xml services/fleet-api/pom.xml
COPY services/telemetry-gateway/pom.xml services/telemetry-gateway/pom.xml
COPY services/telemetry-processor/pom.xml services/telemetry-processor/pom.xml
COPY simulators/vehicle-simulator/pom.xml simulators/vehicle-simulator/pom.xml

COPY services/fleet-api/src services/fleet-api/src

RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode --no-transfer-progress \
    --projects services/fleet-api \
    --also-make \
    -DskipTests \
    package

FROM ${JAVA_RUNTIME_IMAGE} AS runtime

RUN addgroup -S -g 10001 fleetpulse \
    && adduser -S -D -H -u 10001 -G fleetpulse fleetpulse

WORKDIR /app
COPY --from=build --chown=10001:10001 \
    /workspace/services/fleet-api/target/fleet-api-*.jar app.jar

USER 10001:10001
EXPOSE 8080
STOPSIGNAL SIGTERM

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
