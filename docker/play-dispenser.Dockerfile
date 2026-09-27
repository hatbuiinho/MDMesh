# Build the audited private-account dispenser from a pinned upstream commit.
FROM node:22-alpine AS source
ARG DISPENSER_COMMIT=f8615ac5f0cdebc049320abc44a6bb94426861c5
RUN apk add --no-cache git
RUN git clone https://github.com/rehmatworks/gplaydl-dispenser.git /src \
 && cd /src && git checkout "$DISPENSER_COMMIT"
WORKDIR /src/web
RUN npm ci && npm run build

FROM golang:1.26-alpine AS build
WORKDIR /src
COPY --from=source /src /src
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/dispenser ./cmd/dispenser

FROM alpine:3.22
LABEL org.opencontainers.image.source="https://github.com/rehmatworks/gplaydl-dispenser" \
      org.opencontainers.image.licenses="GPL-3.0-only"
RUN apk add --no-cache ca-certificates \
 && addgroup -S dispenser && adduser -S -G dispenser -h /data dispenser
COPY --from=build /out/dispenser /usr/local/bin/dispenser
COPY --from=source /src/resources /app/resources
# The pinned upstream commit declares GPL-3.0-only in README.md but does not
# publish a standalone LICENSE file, so retain that notice in the image.
COPY --from=source /src/README.md /usr/share/licenses/gplaydl-dispenser/README.md
WORKDIR /app
USER dispenser
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/dispenser"]
