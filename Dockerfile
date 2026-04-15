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

# Copy native C source and pre-built libraries
COPY native/src/main/c/ native/src/main/c/
COPY native/libs/ native/libs/

# Build native library from source (musl, for Alpine runtime)
# Compiles for the target platform (amd64 or arm64) via buildx
RUN apk add --no-cache gcc musl-dev linux-headers && \
    JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java)))) && \
    gcc -shared -fPIC -O2 \
      -I${JAVA_HOME}/include -I${JAVA_HOME}/include/linux \
      -o native/libs/libslogr-native-musl.so \
      native/src/main/c/twampUdp.c native/src/main/c/traceroute.c \
    || echo "WARNING: Native lib compilation failed"

# Build shadow JAR — tests are already run by CI before this stage
RUN ./gradlew :app:shadowJar --no-daemon -x test

# ── Stage 2: minimal runtime image ────────────────────────────────────────────
FROM amazoncorretto:21-alpine

# CAP_NET_RAW          — required for traceroute (ICMP raw sockets)
# CAP_NET_BIND_SERVICE — required for binding port 862 as non-root
# SLOGR_TEST_PORT      — fixed UDP port for TWAMP test sessions (default 863)
#
# To bind port 862 as non-root, use ONE of:
#   docker run --sysctl net.ipv4.ip_unprivileged_port_start=862 ...  (preferred, Docker 20.10+)
#   docker run --cap-add NET_BIND_SERVICE --cap-add NET_RAW ...       (fallback)
# Do NOT use setcap on the java binary — it grants capabilities to all
# Java processes in the container, not just the agent.

RUN apk add --no-cache bash libcap && \
    addgroup -S slogr && \
    adduser  -S -G slogr -h /opt/slogr -s /sbin/nologin slogr && \
    mkdir -p /opt/slogr/lib /opt/slogr/data /opt/slogr/logs /etc/slogr && \
    chown -R slogr:slogr /opt/slogr && \
    touch /etc/slogr/env && chmod 600 /etc/slogr/env

COPY --from=builder /build/app/build/libs/slogr-agent-all.jar /opt/slogr/slogr-agent-all.jar

# musl-compiled native library for Alpine (amd64 or arm64 depending on build platform)
# SlogrNative.isMusl() detects Alpine and looks for the -musl suffix
COPY --from=builder /build/native/libs/libslogr-native-musl.so /opt/slogr/lib/libslogr-native-musl.so

COPY deploy/wrapper.sh /usr/local/bin/slogr-agent
RUN chmod +x /usr/local/bin/slogr-agent && \
    chown slogr:slogr /opt/slogr/slogr-agent-all.jar /opt/slogr/lib/libslogr-native-musl.so

USER slogr
WORKDIR /opt/slogr

ENV SLOGR_DATA_DIR=/opt/slogr/data
ENV SLOGR_NATIVE_DIR=/opt/slogr/lib
ENV SLOGR_TEST_PORT=863
ENV JAVA_OPTS="-Xmx384m"

EXPOSE 862/tcp 862/udp 863/udp

ENTRYPOINT ["/usr/local/bin/slogr-agent"]
CMD ["daemon"]
