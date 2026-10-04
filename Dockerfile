# FotoTimeline in un'immagine sola: Angular compilato dentro il jar di Spring Boot.

FROM node:22-alpine AS frontend
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
# angular.json scrive in ../backend/src/main/resources/static
RUN mkdir -p ../backend/src/main/resources && npm run build

FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /src/backend
COPY backend/pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /src/backend/src/main/resources/static ./src/main/resources/static
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre
# curl per l'healthcheck; heif-convert (+ decoder HEVC) per gli HEIC; ffmpeg per i video.
RUN apt-get update && apt-get install -y --no-install-recommends \
        curl ffmpeg libheif-examples libheif-plugin-libde265 \
    && rm -rf /var/lib/apt/lists/*
# Stesso uid di marco su server2: scrive nel cloud montato da rclone con --uid 1000.
RUN userdel -r ubuntu 2>/dev/null || true; useradd --uid 1000 --create-home fototimeline
USER fototimeline
WORKDIR /app
COPY --from=backend /src/backend/target/fototimeline-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
