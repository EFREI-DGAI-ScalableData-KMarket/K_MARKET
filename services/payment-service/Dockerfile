# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Build stage — compile the Spring Boot app and package the executable fat JAR.
# Java 25 (see <java.version>25</java.version> in pom.xml), built with the
# Maven wrapper so the Maven version matches the project.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build

WORKDIR /app

# Copy the Maven wrapper first so dependency resolution is cached across builds
# as long as the POM does not change.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -ntp dependency:go-offline

# Copy the sources and build the fat JAR.
# Tests are skipped because they rely on Testcontainers (a Docker daemon),
# which is not available inside the image build.
COPY src/ src/
RUN ./mvnw -B -ntp -DskipTests clean package

# ---------------------------------------------------------------------------
# Runtime stage — ship only a JRE and the built JAR.
# The service serves everything under /v1 on port 8003 (see application.properties).
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8003

ENTRYPOINT ["java", "-jar", "app.jar"]
