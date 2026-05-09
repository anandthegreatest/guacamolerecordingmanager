FROM maven:3.9.11-eclipse-temurin-25

WORKDIR /app

COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B package -DskipTests

ENV RECORDINGS_ROOT=/recordings
EXPOSE 8080

CMD ["java", "-jar", "target/recording-player-0.0.1-SNAPSHOT.jar"]
