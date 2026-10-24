# Multi-stage build so the machine running Docker needs neither Java nor Gradle installed.

# Stage 1: build the executable Spring Boot jar.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Copy the Gradle wrapper and build scripts first so dependency resolution is cached.
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./

# The wrapper script was authored on Windows; strip CR so it runs under the Linux shell.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew

COPY src src

# bootJar produces only the executable jar (the plain jar task is not a dependency of it).
RUN ./gradlew bootJar --no-daemon

# Stage 2: slim runtime image with just a JRE and the built jar.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
