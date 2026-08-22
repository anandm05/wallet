# pom.xml declares java.version=17; build with the same JDK the project
# targets - JDK 25 silently breaks Lombok's annotation processing here.
FROM maven:3.9.16-amazoncorretto-17-al2023 AS build
WORKDIR /app

# 1. Force absolute container context paths
COPY wallet/pom.xml .
COPY wallet/src ./src

# 2. Bypass layer dependency snapshots to avoid freezing the cache
RUN mvn clean package -DskipTests

# Stage 2: Low-footprint, isolated execution image
FROM amazoncorretto:17-alpine
WORKDIR /app
COPY --from=build /app/target/wallet-0.0.1-SNAPSHOT.jar app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]