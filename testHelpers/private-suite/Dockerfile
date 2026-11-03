FROM node:16-bullseye-slim AS node
FROM eclipse-temurin:11-jdk-jammy
COPY --from=node /usr/local/bin/node /usr/local/bin/node
ENV ANDROID_HOME=/opt/android ANDROID_SDK_ROOT=/opt/android
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip procps && rm -rf /var/lib/apt/lists/*
RUN mkdir -p /opt/android/platforms /opt/android/build-tools && curl -fsSL https://dl.google.com/android/repository/platform-29_r05.zip -o /tmp/platform.zip && unzip -q /tmp/platform.zip -d /opt/android/platforms && mv /opt/android/platforms/android-10 /opt/android/platforms/android-29 && curl -fsSL https://dl.google.com/android/repository/build-tools_r29.0.2-linux.zip -o /tmp/tools.zip && unzip -q /tmp/tools.zip -d /opt/android/build-tools && mv /opt/android/build-tools/android-10 /opt/android/build-tools/29.0.2 && rm /tmp/platform.zip /tmp/tools.zip
