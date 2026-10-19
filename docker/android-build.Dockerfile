FROM eclipse-temurin:17-jdk-jammy

ARG BUILD_UID=1000
ARG BUILD_GID=1000
ENV ANDROID_HOME=/opt/android-sdk \
    GRADLE_USER_HOME=/home/builder/.gradle
ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"

RUN apt-get update && apt-get install -y --no-install-recommends curl unzip ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL --retry 3 https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip -o /tmp/tools.zip \
    && echo '5fdcc763663eefb86a5b8879697aa6088b041e70  /tmp/tools.zip' | sha1sum -c - \
    && mkdir -p "${ANDROID_HOME}/cmdline-tools" \
    && unzip -q /tmp/tools.zip -d /tmp/android-tools \
    && mv /tmp/android-tools/cmdline-tools "${ANDROID_HOME}/cmdline-tools/latest" \
    && rm -rf /tmp/tools.zip /tmp/android-tools
# SDK package installation requires acceptance of Google's SDK licenses.
RUN yes | sdkmanager --licenses >/dev/null \
    && sdkmanager 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0' 'build-tools;34.0.0'

RUN groupadd --gid "${BUILD_GID}" builder \
    && useradd --uid "${BUILD_UID}" --gid "${BUILD_GID}" --create-home builder \
    && mkdir -p /home/builder/.gradle /home/builder/.android \
    && chown -R builder:builder /home/builder
COPY android-build-entrypoint.sh /usr/local/bin/android-build
USER builder
WORKDIR /home/builder
ENTRYPOINT ["bash", "/usr/local/bin/android-build"]
CMD [":app:assembleDebug"]
