FROM eclipse-temurin:21-jre
ARG JAR_FILE
COPY ${JAR_FILE} /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
