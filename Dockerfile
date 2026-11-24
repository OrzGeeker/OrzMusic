# ================================
# Build image
# ================================
# Release Linux SDKs are built on Ubuntu 24.04 and require glibc 2.38.
FROM swift:6.1-noble AS build-base

# Build identity arguments (pass via --build-arg or CI)
ARG APP_VERSION
ARG GIT_COMMIT
ARG BUILD_TIME

# Set up a build area
WORKDIR /build

# First just resolve dependencies.
COPY ./Package.* ./
RUN swift package resolve

# Copy entire repo into container
COPY . .

# Release SDK download/checksum tools only; decoder libraries remain fully
# contained in the immutable OrzAudioCore artifact.
RUN apt-get -q update \
    && apt-get -q install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*

# Install the immutable full/server SDK. The script verifies the release asset
# checksum and manifest version from audio-core-sdk.lock.json.
RUN ./script/update-audio-core-server.sh

# Fast cross-platform validation target. It links the official Swift binding
# to the released Linux SDK and renders audible PCM without compiling Vapor.
FROM build-base AS audio-core-smoke
RUN swift build --product OrzAudioCoreSmoke \
    && cp "$(swift build --show-bin-path)/OrzAudioCoreSmoke" /usr/local/bin/OrzAudioCoreSmoke
ENV LD_LIBRARY_PATH=/build/.audio-core-sdk/server/native/lib
ENTRYPOINT ["/usr/local/bin/OrzAudioCoreSmoke"]

FROM build-base AS build

# Build the service against the immutable OrzAudioCore v1.4.0 ABI-v1 SDK.
RUN swift build -c release --product OrzMusicService

# Switch to the staging area
WORKDIR /staging

# Copy main executable to staging area
RUN cp "$(swift build --package-path /build -c release --show-bin-path)/OrzMusicService" ./Run

# Runtime decoder SDK and its license/SBOM metadata.
RUN mkdir -p ./lib ./audio-core-metadata \
    && cp /build/.audio-core-sdk/server/native/lib/libOrzAudioCore.so ./lib/ \
    && cp -Ra /build/.audio-core-sdk/server/licenses ./audio-core-metadata/ \
    && cp -Ra /build/.audio-core-sdk/server/metadata ./audio-core-metadata/

# Copy resources bundled by SPM to staging area
RUN find -L "$(swift build --package-path /build -c release --show-bin-path)/" -regex '.*\.resources$' -exec cp -Ra {} ./ \;

# Copy any resources from the public directory and views directory if the directories exist
RUN [ -d /build/Resources ] && { cp -Ra /build/Resources ./Resources && chmod -R a-w ./Resources; } || true

# Copy VERSION file for diagnostic fallback
RUN [ -f /build/VERSION ] && cp /build/VERSION ./VERSION || true

# ================================
# Run image
# ================================
FROM swift:6.1-noble-slim

# Re-declare build args for this stage
ARG APP_VERSION
ARG GIT_COMMIT
ARG BUILD_TIME

# Build identity environment variables
ENV APP_VERSION=${APP_VERSION}
ENV GIT_COMMIT=${GIT_COMMIT}
ENV BUILD_TIME=${BUILD_TIME}

# OCI image labels
LABEL org.opencontainers.image.created=${BUILD_TIME}
LABEL org.opencontainers.image.version=${APP_VERSION}
LABEL org.opencontainers.image.revision=${GIT_COMMIT}
LABEL org.opencontainers.image.title="OrzMusic Service"
LABEL org.opencontainers.image.description="Music management and streaming API for OrzPlayer"

# Make sure all system packages are up to date, and install runtime deps.
RUN export DEBIAN_FRONTEND=noninteractive DEBCONF_NONINTERACTIVE_SEEN=true \
    && apt-get -q -o Acquire::Retries=5 -o Acquire::http::Timeout=30 update \
    && apt-get -q -o Acquire::Retries=5 -o Acquire::http::Timeout=30 install -y --no-install-recommends \
      ca-certificates \
      tzdata \
      ffmpeg \
      curl \
    && rm -rf /var/lib/apt/lists/*

# Create a vapor user and group with /app as its home directory
RUN useradd --user-group --create-home --system --skel /dev/null --home-dir /app vapor

# Switch to the new home directory
WORKDIR /app

ENV LD_LIBRARY_PATH=/app/lib

# Copy built executable and any staged resources from builder
COPY --from=build --chown=vapor:vapor /staging /app

# Ensure all further commands run as the vapor user
USER vapor:vapor

# Let Docker bind to port 8080
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD curl -f http://localhost:8080/api/health || exit 1

# Start the Vapor service
ENTRYPOINT ["./Run"]
CMD ["serve", "--env", "production", "--hostname", "0.0.0.0", "--port", "8080"]
