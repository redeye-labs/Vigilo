# Vigilo standalone: lo stesso server del componente aggiuntivo Home Assistant, in un container
# Docker qualsiasi (amd64 o arm64), senza Home Assistant né Supervisor.
# Da costruire dalla radice del repository:
#   docker build -f standalone/Dockerfile -t vigilo .
# Il codice è quello di addon/vigilo/server, avviato con "-mode standalone".

# 1) Server Vigilo (Go, statico). Compila sempre per l'architettura di destinazione (TARGETARCH),
#    anche quando buildx costruisce più architetture da una sola macchina.
FROM --platform=$BUILDPLATFORM golang:1.27-bookworm AS build
ARG BUILD_VERSION=standalone-dev
ARG TARGETOS
ARG TARGETARCH
WORKDIR /src
COPY addon/vigilo/server/go.mod addon/vigilo/server/go.sum ./
RUN go mod download
COPY addon/vigilo/server/ ./
RUN CGO_ENABLED=0 GOOS=${TARGETOS:-linux} GOARCH=${TARGETARCH} \
    go build -trimpath -ldflags "-s -w -X main.version=${BUILD_VERSION}" -o /out/vigilo .

# 2) Server Fleet Telemetry ufficiale di Tesla (immagine multi-arch amd64/arm64), stessa versione dell'add-on
FROM tesla/fleet-telemetry:v0.10.0 AS fleettelemetry

# 3) Immagine finale: Debian minimale + mosquitto (broker interno, solo su 127.0.0.1)
FROM debian:bookworm-slim
ARG BUILD_VERSION=standalone-dev
LABEL \
    org.opencontainers.image.title="Vigilo (standalone)" \
    org.opencontainers.image.description="Avvisi Sentinella in tempo reale e backend per l'app Vigilo, senza Home Assistant" \
    org.opencontainers.image.version="${BUILD_VERSION}" \
    org.opencontainers.image.source="https://github.com/redeye-labs/Vigilo" \
    org.opencontainers.image.licenses="MIT"
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates libstdc++6 mosquitto tzdata \
    && rm -rf /var/lib/apt/lists/*
COPY --from=fleettelemetry /fleet-telemetry /usr/bin/fleet-telemetry
COPY --from=build /out/vigilo /usr/bin/vigilo
COPY addon/vigilo/THIRD_PARTY_LICENSES.md addon/vigilo/licenses/ /usr/share/licenses/vigilo/
ENV VIGILO_MODE=standalone \
    TZ=Europe/Rome
VOLUME ["/data"]
EXPOSE 8099
WORKDIR /data
HEALTHCHECK --interval=30s --timeout=10s --start-period=20s --retries=3 \
    CMD ["/usr/bin/vigilo", "-healthcheck", "http://127.0.0.1:8099/healthz"]
CMD ["/usr/bin/vigilo", "-mode", "standalone", "-data", "/data", "-ingress", ":8099", \
     "-fleet-telemetry", "/usr/bin/fleet-telemetry", "-mosquitto", "/usr/sbin/mosquitto"]
