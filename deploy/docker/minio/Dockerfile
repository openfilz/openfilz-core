# OpenFilz MinIO image — ghcr.io/openfilz/minio:<MINIO_REF>
#
# MinIO stopped publishing images (quay.io/minio/* and Docker Hub minio/* are gone) and the
# upstream repository is no longer maintained. This image is built from source:
#   - server: chainguard-forks/minio (the maintained fork chainguard/minio is built from)
#   - client: minio/mc (last upstream release)
# It is a drop-in for the former quay.io/minio/minio image: entrypoint `minio`, runs as
# root (volumes written by the old image stay usable), and also ships `mc`, `curl` and
# `/bin/sh` so the same image serves the healthchecks and bucket-init / backup jobs.
#
# Published by .github/workflows/build-minio.yml. Bump MINIO_REF to a newer fork tag
# (git ls-remote --tags https://github.com/chainguard-forks/minio) to update.

ARG MINIO_REF=RELEASE.2026-09-22T19-25-18Z
ARG MC_REF=RELEASE.2025-08-13T08-35-41Z

FROM golang:1.26-alpine AS build
ARG MINIO_REF
ARG MC_REF
ENV CGO_ENABLED=0 GOTOOLCHAIN=auto
RUN apk add --no-cache git
WORKDIR /src

# RELEASE.2026-09-22T19-25-18Z -> 2026-09-22T19:25:18Z (the version format gen-ldflags expects)
RUN git clone --depth 1 --branch "$MINIO_REF" https://github.com/chainguard-forks/minio.git minio \
 && cd minio \
 && VERSION=$(echo "$MINIO_REF" | sed -E 's/^RELEASE\.(.{10})T(..)-(..)-(..)Z$/\1T\2:\3:\4Z/') \
 && go build -tags kqueue -trimpath \
      -ldflags "$(MINIO_RELEASE=RELEASE go run buildscripts/gen-ldflags.go "$VERSION")" -o /out/minio

RUN git clone --depth 1 --branch "$MC_REF" https://github.com/minio/mc.git mc \
 && cd mc \
 && VERSION=$(echo "$MC_REF" | sed -E 's/^RELEASE\.(.{10})T(..)-(..)-(..)Z$/\1T\2:\3:\4Z/') \
 && go build -tags kqueue -trimpath \
      -ldflags "$(MC_RELEASE=RELEASE go run buildscripts/gen-ldflags.go "$VERSION")" -o /out/mc

FROM alpine:3.22
ARG MINIO_REF
LABEL org.opencontainers.image.title="minio" \
      org.opencontainers.image.description="MinIO server + mc built from chainguard-forks/minio ${MINIO_REF}" \
      org.opencontainers.image.source="https://github.com/openfilz/openfilz-core" \
      org.opencontainers.image.licenses="AGPL-3.0-only" \
      org.opencontainers.image.version="${MINIO_REF}"
RUN apk add --no-cache ca-certificates curl tzdata
COPY --from=build /out/minio /out/mc /usr/bin/
EXPOSE 9000 9001
VOLUME ["/data"]
ENTRYPOINT ["minio"]
CMD ["server", "/data"]
