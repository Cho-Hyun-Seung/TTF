FROM eclipse-temurin:17-jdk-alpine AS build

WORKDIR /workspace

COPY --chmod=0755 gradlew ./gradlew
COPY settings.gradle build.gradle lombok.config ./
COPY gradle ./gradle
COPY src ./src

RUN ./gradlew test bootJar --no-daemon

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

RUN addgroup -S ttf && adduser -S -G ttf ttf

COPY --from=build --chown=ttf:ttf /workspace/build/libs/*.jar ./app.jar

USER ttf

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
