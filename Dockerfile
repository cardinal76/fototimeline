# FotoTimeline in un'immagine sola: Angular compilato dentro il jar di Spring Boot.

FROM node:22-alpine AS frontend
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
# angular.json scrive in ../backend/src/main/resources/static
RUN mkdir -p ../backend/src/main/resources && npm run build

# Luoghi dal GPS, offline: GeoNames (CC BY 4.0, https://www.geonames.org/). Stage a
# parte: Docker lo tiene in cache e non riscarica a ogni rilascio. Per aggiornare
# il dataset: docker build --no-cache-filter geonames ...
FROM alpine:3.20 AS geonames
RUN apk add --no-cache curl unzip
WORKDIR /geonames
ARG GEONAMES=https://download.geonames.org/export/dump
# cities500: tutti i centri sopra i 500 abitanti (~225 mila punti, in Italia anche
# molte frazioni). Senza la colonna dei nomi alternativi: da 41 a 25 MB.
RUN curl -fsSL --retry 3 -o cities500.zip "$GEONAMES/cities500.zip" \
    && unzip -p cities500.zip cities500.txt | awk -F'\t' 'BEGIN { OFS = "\t" } { $4 = ""; print }' > cities500.txt \
    && rm cities500.zip \
    && curl -fsSL --retry 3 -o admin1CodesASCII.txt "$GEONAMES/admin1CodesASCII.txt" \
    && curl -fsSL --retry 3 -o countryInfo.txt "$GEONAMES/countryInfo.txt"
# I nomi in italiano (Roma, Parigi, Baviera): da alternateNamesV2 (200 MB compresso,
# quasi 800 in chiaro) solo le righe "it" dei luoghi e delle regioni qui sopra, ~1 MB.
RUN curl -fsSL --retry 3 -o alternateNamesV2.zip "$GEONAMES/alternateNamesV2.zip" \
    && unzip -p alternateNamesV2.zip alternateNamesV2.txt \
       | awk -F'\t' '$3 == "it"' \
       | awk -F'\t' 'FILENAME == "cities500.txt" { id[$1] = 1; next }; \
                     FILENAME == "admin1CodesASCII.txt" { id[$4] = 1; next }; \
                     ($2 in id)' cities500.txt admin1CodesASCII.txt - > alternateNames-it.txt \
    && rm alternateNamesV2.zip \
    && test "$(wc -l < cities500.txt)" -gt 100000 && test -s alternateNames-it.txt \
    && ls -la

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
# Il volume delle miniature, appena creato, prende proprietario e permessi da qui.
RUN mkdir -p /miniature && chown fototimeline:fototimeline /miniature
USER fototimeline
WORKDIR /app
COPY --from=backend /src/backend/target/fototimeline-*.jar app.jar
COPY --from=geonames /geonames /app/geonames
ENV FOTOTIMELINE_LUOGHI=/app/geonames
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
