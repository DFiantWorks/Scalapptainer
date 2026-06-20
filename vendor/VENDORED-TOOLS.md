# Vendored installer tools

Scalapptainer bundles a few small static Linux binaries so that Apptainer's
unprivileged installer can run inside a WSL2/Lima backend **without root**, even on a
minimal distro. They live under
`scalapptainer/resources/scalapptainer/tools/linux-<arch>/` and are materialised into
the backend cache only when the corresponding system tool is missing (system copies
are always preferred). Refresh them with [`refresh-tools.sh`](./refresh-tools.sh).

| Tool | Arches | Version | Source | License | SHA256 |
|------|--------|---------|--------|---------|--------|
| `rpm2cpio` | x86_64, aarch64 | n/a (committed POSIX script) | this repo | Apache-2.0 (this project) | — |
| `curl` | x86_64 | 8.20.0 (static, musl) | [stunnel/static-curl](https://github.com/stunnel/static-curl) | [curl license](https://curl.se/docs/copyright.html) (MIT-style) | `58c6fab6e3f62d39d23224d752de1302cb717d997288d0f23d6fa7e79c393c1f` |
| `curl` | aarch64 | 8.20.0 (static, musl) | stunnel/static-curl | curl license | `32799692a41e88f9f2be85348c2230baf5a0a29ded2d6c086e49e5cbab22b3f4` |
| `busybox` | x86_64 | busybox 1.36.1-r31 (Alpine `busybox-static`) | [Alpine Linux](https://dl-cdn.alpinelinux.org/alpine/v3.20/main/) | **GPL-2.0-only** | `6d4ae568988ee24beb9dac4afdac4df67f90bbaed6ca47628da35ff5eb632a4c` |
| `busybox` | aarch64 | busybox 1.36.1-r31 (Alpine `busybox-static`) | Alpine Linux | **GPL-2.0-only** | `ebd2865edcab0b590c7d0edb70d3e782cbfb541e518a390ced3a3e186509bc7f` |

Notes:

- `busybox` is a static multi-call binary; it dispatches to an applet based on `argv[0]`,
  so Scalapptainer symlinks the applets it needs to it at install time: `cpio` (which
  minimal Debian/Ubuntu/WSL images frequently lack) and the RPM-payload decompressors
  `xz`/`gzip`/`bzip2` that the vendored `rpm2cpio` shells out to. (busybox has no zstd
  applet, so a zstd-compressed RPM still needs a system `zstd`.) busybox is licensed
  **GPL-2.0-only** — its corresponding source is available from <https://www.busybox.net/>
  and the Alpine package repository linked above.
- `curl` static builds are released by the stunnel project under curl's MIT-style license.
- `rpm2cpio` is a small POSIX shell script maintained in this repository (Debian/Ubuntu
  never ship `rpm2cpio`, so there is no upstream binary to vendor).
