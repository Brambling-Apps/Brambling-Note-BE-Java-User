FROM eclipse-temurin:17-jdk-alpine
EXPOSE 8080
LABEL authors="Wordless Echo"

RUN apk update && apk add --upgrade argon2-libs

VOLUME /tmp
COPY build/libs/*.jar app.jar

ENTRYPOINT ["java","-jar","/app.jar","--spring.profiles.active=docker"]
