# pom.xml declares java.version=17; build with the same JDK the project
# targets - JDK 25 silently breaks Lombok's annotation processing here.
FROM maven:3.9.16-amazoncorretto-17-al2023 AS build
WORKDIR /app

# 1. Warm the dependency cache from pom.xml alone, before src exists yet.
# The --mount=type=cache dir persists across builds (unlike a normal layer),
# so repeat builds skip re-downloading ~150-300MB of jars from Central -
# that redownload was the biggest chunk of every rebuild this session.
COPY wallet/pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B dependency:go-offline

# 2. The actual build - deliberately a separate COPY+RUN from step 1, so a
# code change always invalidates this layer and always recompiles. The
# cache mount here only reuses already-downloaded jars; it never skips
# compiling or packaging.
COPY wallet/src ./src
RUN --mount=type=cache,target=/root/.m2 mvn clean package -DskipTests

# Stage 2: Low-footprint, isolated execution image
FROM amazoncorretto:17-alpine
WORKDIR /app
COPY --from=build /app/target/wallet-0.0.1-SNAPSHOT.jar app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]