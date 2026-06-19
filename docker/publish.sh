#!/usr/bin/env bash
#
# One-time, multi-arch publish of the Scalapptainer intro-demo images to GHCR.
#
# These images are static (a fixed tool on top of ubuntu:24.04) and are meant to
# be pushed once; there is no need to rebuild them on every change. Re-run this
# only if you deliberately change a Dockerfile here.
#
# Prerequisites:
#   - Docker with Buildx (Docker Desktop includes both).
#   - QEMU emulators for cross-arch builds, if your host is single-arch:
#       docker run --privileged --rm tonistiigi/binfmt --install all
#   - Auth to GHCR with a token that has `write:packages`:
#       echo "$GHCR_TOKEN" | docker login ghcr.io -u <github-user> --password-stdin
#
# After the first push, make each package PUBLIC once (GitHub -> the org's
# Packages -> package -> Package settings -> Change visibility) so `apptainer
# pull` works without authentication.

set -euo pipefail

REGISTRY="${REGISTRY:-ghcr.io/dfiantworks}"
PLATFORMS="${PLATFORMS:-linux/amd64,linux/arm64}"
TAG="${TAG:-latest}"
IMAGES=(cowsay xeyes)

cd "$(dirname "$0")"

# A docker-container builder is required to build and push multi-arch in one go.
BUILDER="scalapptainer-demos"
docker buildx use "$BUILDER" 2>/dev/null || docker buildx create --use --name "$BUILDER"

for img in "${IMAGES[@]}"; do
  ref="$REGISTRY/scalapptainer-$img:$TAG"
  echo ">> building & pushing $ref for $PLATFORMS"
  docker buildx build \
    --platform "$PLATFORMS" \
    --tag "$ref" \
    --push \
    "$img"
done

echo "Done. Remember to set the new packages to Public if this was the first push."
