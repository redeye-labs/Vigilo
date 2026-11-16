# Immagine base multi-arch (amd64/aarch64): niente più build.yaml, il Dockerfile è l'unica fonte
# (vedi developers.home-assistant.io/blog/2026/04/02/builder-migration).
# Nome diverso da BUILD_FROM: un Supervisor senza build.yaml potrebbe passare la sua base Alpine.
ARG VIGILO_BASE=ghcr.io/home-assistant/base-debian:bookworm

# 1) Server Vigilo (Go, statico)
FROM golang:1.27-bookworm AS build
ARG BUILD_VERSION=dev
WORKDIR /src
COPY server/go.mod server/go.sum ./
RUN go mod download
COPY server/ ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -X main.version=${BUILD_VERSION}" -o /out/vigilo .

# 2) Server Fleet Telemetry ufficiale di Tesla (immagine multi-arch amd64/arm64)
FROM tesla/fleet-telemetry:v0.10.0 AS fleettelemetry

# 3) Immagine finale
FROM ${VIGILO_BASE}
ARG BUILD_VERSION=dev
LABEL \
    io.hass.name="Vigilo" \
    io.hass.description="Avvisi Sentinella in tempo reale e backend per l'app Vigilo" \
    io.hass.type="app" \
    io.hass.version="${BUILD_VERSION}" \
    io.hass.url="https://github.com/redeye-labs/Vigilo" \
    org.opencontainers.image.title="Vigilo" \
    org.opencontainers.image.source="https://github.com/redeye-labs/Vigilo" \
    org.opencontainers.image.licenses="MIT"
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates libstdc++6 \
    && rm -rf /var/lib/apt/lists/*
COPY --from=fleettelemetry /fleet-telemetry /usr/bin/fleet-telemetry
COPY --from=build /out/vigilo /usr/bin/vigilo
# s6-overlay (/init dell'immagine base) di default azzera l'ambiente del processo principale:
# senza questo il server non vede SUPERVISOR_TOKEN (il server legge comunque anche /run/s6/container_environment).
ENV S6_KEEP_ENV=1
WORKDIR /data
CMD ["/usr/bin/vigilo", "-data", "/data", "-ingress", ":8099", "-fleet-telemetry", "/usr/bin/fleet-telemetry"]
