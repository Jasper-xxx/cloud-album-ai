# syntax=docker/dockerfile:1.7

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

COPY --from=build /workspace/target/*.jar /app/app.jar

EXPOSE 8088 8089

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
