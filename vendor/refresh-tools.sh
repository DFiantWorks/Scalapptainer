#!/usr/bin/env bash
#
# refresh-tools.sh — (re)download the static Linux binaries that Scalapptainer
# vendors so Apptainer's unprivileged installer can run inside a backend without
# root. Output goes under scalapptainer/resources/scalapptainer/tools/linux-<arch>/.
#
#   curl   — fully-static musl build from github.com/stunnel/static-curl.
#            Used by Apptainer's install-unprivileged.sh to fetch RPMs.
#   cpio   — the static busybox from Alpine's busybox-static package; busybox runs
#            its cpio applet when invoked as `cpio` (argv[0]). Debian/Ubuntu/WSL
#            minimal images frequently lack cpio.
#   rpm2cpio — NOT downloaded: it is a committed POSIX script (rpm2cpio is never
#            shipped by Debian/Ubuntu). Edit the committed file to change it.
#
# Every artifact is pinned and verified against the recorded SHA256 below. Run from
# anywhere; re-run to bump versions (update the pins + sums). Commit the binaries.
#
# Usage:
#   vendor/refresh-tools.sh                 # all arches
#   vendor/refresh-tools.sh x86_64          # one arch
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
res="$repo_root/scalapptainer/resources/scalapptainer/tools"

# --- Pins -------------------------------------------------------------------
CURL_VER="8.20.0"                 # github.com/stunnel/static-curl release tag
ALPINE_REL="v3.20"                # Alpine release providing busybox-static
BUSYBOX_APK="busybox-static-1.36.1-r31.apk"

url_for() {
  local arch="$1" tool="$2"
  case "$tool:$arch" in
    curl:x86_64)  echo "https://github.com/stunnel/static-curl/releases/download/${CURL_VER}/curl-linux-x86_64-musl-${CURL_VER}.tar.xz" ;;
    curl:aarch64) echo "https://github.com/stunnel/static-curl/releases/download/${CURL_VER}/curl-linux-aarch64-musl-${CURL_VER}.tar.xz" ;;
    cpio:x86_64)  echo "https://dl-cdn.alpinelinux.org/alpine/${ALPINE_REL}/main/x86_64/${BUSYBOX_APK}" ;;
    cpio:aarch64) echo "https://dl-cdn.alpinelinux.org/alpine/${ALPINE_REL}/main/aarch64/${BUSYBOX_APK}" ;;
    *) return 1 ;;
  esac
}

# Recorded SHA256 of the *materialised* binary (post-extraction). Empty => the run
# prints the computed sum instead of verifying (use when bumping pins).
declare -A SHA256=(
  ["curl:x86_64"]="58c6fab6e3f62d39d23224d752de1302cb717d997288d0f23d6fa7e79c393c1f"
  ["curl:aarch64"]="32799692a41e88f9f2be85348c2230baf5a0a29ded2d6c086e49e5cbab22b3f4"
  ["cpio:x86_64"]="6d4ae568988ee24beb9dac4afdac4df67f90bbaed6ca47628da35ff5eb632a4c"
  ["cpio:aarch64"]="ebd2865edcab0b590c7d0edb70d3e782cbfb541e518a390ced3a3e186509bc7f"
)

tools=(curl cpio)
arches=("$@"); [ ${#arches[@]} -eq 0 ] && arches=(x86_64 aarch64)

fetch() {
  local arch="$1" tool="$2" url dest tmp sum want
  url="$(url_for "$arch" "$tool")" || { echo "no source for $tool/$arch, skipping"; return 0; }
  dest="$res/linux-$arch/$tool"
  mkdir -p "$res/linux-$arch"
  tmp="$(mktemp -d)"
  echo ">> $tool/$arch <- $url"
  curl -fsSL "$url" -o "$tmp/dl"

  case "$tool" in
    curl)  tar -C "$tmp" -xf "$tmp/dl"; cp "$tmp/curl" "$dest" ;;       # tar.xz with a `curl` binary
    cpio)  tar -C "$tmp" -xzf "$tmp/dl" 2>/dev/null || true             # .apk is a gzip tar
           cp "$(find "$tmp" -name busybox.static | head -1)" "$dest" ;;
  esac
  chmod +x "$dest"
  rm -rf "$tmp"

  sum="$(sha256sum "$dest" | awk '{print $1}')"
  want="${SHA256[$tool:$arch]:-}"
  if [ -z "$want" ]; then echo "   sha256=$sum  (record it)";
  elif [ "$want" != "$sum" ]; then echo "   !! checksum mismatch: got $sum want $want" >&2; exit 1;
  else echo "   sha256 ok"; fi
}

for arch in "${arches[@]}"; do
  for tool in "${tools[@]}"; do fetch "$arch" "$tool"; done
done
echo "Done. Review and commit the binaries under $res/."
