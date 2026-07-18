# Stage 1: Build the application
FROM maven:3.9.9-amazoncorretto-25-al2023 AS build
WORKDIR /app
COPY pom.xml .
# Cache dependencies
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Minimal runtime environment
FROM amazoncorretto:25-alpine
WORKDIR /app
COPY --from=build /app/target/wallet-0.0.1-SNAPSHOT.jar app.jar

# Low-resource JVM optimization
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]