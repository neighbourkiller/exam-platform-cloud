FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /workspace
COPY . .
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
ARG JAR_FILE
COPY --from=builder /workspace/${JAR_FILE} /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
