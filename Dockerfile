FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY gradlew build.gradle settings.gradle ./
COPY gradle gradle
COPY src src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:25-jdk
WORKDIR /app
COPY --from=build /app/build/libs/energosbyt.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
