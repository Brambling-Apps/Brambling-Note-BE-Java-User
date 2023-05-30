FROM eclipse-temurin:17-jdk-alpine
EXPOSE 8080
LABEL authors="Wordless Echo"

VOLUME /tmp
COPY build/libs/*.jar app.jar

ENTRYPOINT ["java","-jar","/app.jar"]
