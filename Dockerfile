# ── Stage 1: build the shadow JAR ─────────────────────────────────────────────
FROM amazoncorretto:21-alpine AS builder

WORKDIR /build

# Copy build scripts first for dependency layer caching
COPY settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
COPY gradlew gradlew.bat ./
COPY contracts/build.gradle.kts contracts/
COPY engine/build.gradle.kts engine/
COPY native/build.gradle.kts native/
COPY platform/build.gradle.kts platform/
COPY app/build.gradle.kts app/

RUN apk add --no-cache bash git && chmod +x gradlew

# Warm the dependency cache
RUN ./gradlew dependencies --no-daemon -q 2>/dev/null || true

# Copy source
COPY contracts/src contracts/src/
COPY engine/src   engine/src/
COPY native/src   native/src/
COPY platform/src platform/src/
COPY app/src      app/src/

# Copy pre-built native libraries assembled by build-native.yml CI
# musl variant used for Alpine; glibc variant embedded for non-Alpine deployments
COPY native/build/libs/ native/build/libs/

# Build shadow JAR — tests are already run by CI before this stage
RUN ./gradlew :app:shadowJar --no-daemon -x test

# ── Stage 2: minimal runtime image ────────────────────────────────────────────
FROM amazoncorretto:21-alpine

# CAP_NET_RAW      — required for TWAMP raw socket operations
# CAP_NET_BIND_SERVICE — required for binding port 862 as non-root

RUN apk add --no-cache libcap && \
    addgroup -S slogr && \
    adduser  -S -G slogr -h /opt/slogr -s /sbin/nologin slogr && \
    mkdir -p /opt/slogr/lib /opt/slogr/data /opt/slogr/logs /etc/slogr && \
    chown -R slogr:slogr /opt/slogr && \
    touch /etc/slogr/env && chmod 600 /etc/slogr/env

COPY --from=builder /build/app/build/libs/slogr-agent-all.jar /opt/slogr/slogr-agent-all.jar

# musl-compiled native library for Alpine
COPY --from=builder /build/native/build/libs/libslogr-native-linux-amd64-musl.so /opt/slogr/lib/libslogr-native.so

COPY deploy/wrapper.sh /usr/local/bin/slogr-agent
RUN chmod +x /usr/local/bin/slogr-agent && \
    chown slogr:slogr /opt/slogr/slogr-agent-all.jar /opt/slogr/lib/libslogr-native.so

USER slogr
WORKDIR /opt/slogr

ENV SLOGR_DATA_DIR=/opt/slogr/data
ENV SLOGR_NATIVE_DIR=/opt/slogr/lib
ENV JAVA_OPTS="-Xmx384m"

ENTRYPOINT ["/usr/local/bin/slogr-agent"]
CMD ["daemon"]
