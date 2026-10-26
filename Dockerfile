# Stage 1: Build the Angular app
FROM node:24-alpine AS angular-build

WORKDIR /angular-app

COPY ./booklore-ui/package.json ./booklore-ui/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm config set registry https://registry.npmjs.org/ \
    && npm ci --force

COPY ./booklore-ui /angular-app/

RUN npm run build --configuration=production

# Stage 2: Build the Spring Boot app with Gradle
FROM gradle:9.3.1-jdk25-alpine AS springboot-build

WORKDIR /springboot-app

# Copy only build files first to cache dependencies
COPY ./booklore-api/build.gradle ./booklore-api/settings.gradle /springboot-app/

# Download dependencies (cached layer)
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle dependencies --no-daemon

COPY ./booklore-api/src /springboot-app/src

# Copy Angular dist into Spring Boot static resources so it's embedded in the JAR
COPY --from=angular-build /angular-app/dist/booklore/browser /springboot-app/src/main/resources/static

# Inject version into application.yaml using yq
ARG APP_VERSION
RUN apk add --no-cache yq && \
    yq eval '.app.version = strenv(APP_VERSION)' -i /springboot-app/src/main/resources/application.yaml

RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle clean build -x test --no-daemon --parallel

# Stage 3: Final image
FROM eclipse-temurin:25-jre-alpine

ARG APP_VERSION
ARG APP_REVISION

# Set OCI labels
LABEL org.opencontainers.image.title="Trove" \
      org.opencontainers.image.description="Trove: A self-hosted, multi-user digital library with smart shelves, auto metadata, Kobo & KOReader sync, BookDrop imports, OPDS support, and a built-in reader for EPUB, PDF, and comics." \
      org.opencontainers.image.source="https://github.com/X2-Consult/trove" \
      org.opencontainers.image.url="https://github.com/X2-Consult/trove" \
      org.opencontainers.image.documentation="https://github.com/X2-Consult/trove/tree/develop/docs" \
      org.opencontainers.image.version=$APP_VERSION \
      org.opencontainers.image.revision=$APP_REVISION \
      org.opencontainers.image.licenses="AGPL-3.0" \
      org.opencontainers.image.base.name="docker.io/library/eclipse-temurin:25-jre-alpine"

# JVM memory settings adopted from Grimmory (the actively maintained Booklore continuation), same base
# image: Shenandoah's compact mode hands unused heap back to the OS within seconds of a scan or bulk
# metadata run, and metaspace, code cache, direct buffers and thread stacks are capped.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60.0 -XX:+UseShenandoahGC -XX:ShenandoahGCHeuristics=compact -XX:+UseCompactObjectHeaders -XX:InitialRAMPercentage=8.0 -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=48m -Xss512k -XX:CICompilerCount=2 -XX:MaxDirectMemorySize=256m -XX:+UseStringDeduplication -XX:+UnlockExperimentalVMOptions -XX:ShenandoahUncommitDelay=5000 -XX:ShenandoahGuaranteedGCInterval=30000 -XX:+ExitOnOutOfMemoryError"
# Playwright's Node driver and Chromium are glibc builds and can't run on this musl (Alpine) base,
# so Amazon/GoodReads pages use plain HTTP here instead of the headless browser.
ENV METADATA_BROWSER_ENABLED=false

ARG TARGETARCH
# apk upgrade picks up Alpine security fixes released since the base image was built.
RUN apk update && apk upgrade --no-cache && apk add --no-cache su-exec libstdc++ libgcc && \
    mkdir -p /bookdrop

COPY docker/unrar/unrar-${TARGETARCH} /usr/local/bin/unrar
RUN chmod 755 /usr/local/bin/unrar

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh
COPY --from=springboot-build /springboot-app/build/libs/booklore-api-0.0.1-SNAPSHOT.jar /app/app.jar

ARG TROVE_PORT=6060
EXPOSE ${TROVE_PORT}

ENTRYPOINT ["entrypoint.sh"]
CMD ["java", "-jar", "/app/app.jar"]
