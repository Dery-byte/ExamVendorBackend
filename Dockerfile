# BUILD STAGE
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

# RUNTIME STAGE
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/vendor-portal.jar vendor-portal.jar
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "vendor-portal.jar"]
