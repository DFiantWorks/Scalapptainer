# Demo images

The two images behind the README's "Quick taste" examples, so those scripts can
just `Apptainer.pull(...)` instead of building from a def file:

| Image | Pull reference | Contents |
|-------|----------------|----------|
| [`cowsay/`](cowsay/Dockerfile) | `docker://ghcr.io/dfiantworks/scalapptainer-cowsay` | `cowsay` + `fortune` on `ubuntu:24.04`, plus a `moo` command (`fortune \| cowsay`) |
| [`xeyes/`](xeyes/Dockerfile) | `docker://ghcr.io/dfiantworks/scalapptainer-xeyes` | `x11-apps` (`xeyes`) on `ubuntu:24.04` |

Both are published multi-arch (`linux/amd64`, `linux/arm64`) so they pull
natively on Intel/ARM Linux, WSL2 and Apple-Silicon Lima.

## Publishing (one-time)

These images are static and only need to be pushed once. To (re)publish:

```bash
echo "$GHCR_TOKEN" | docker login ghcr.io -u <github-user> --password-stdin
bash docker/publish.sh
```

`publish.sh` builds both images for both architectures and pushes them to GHCR.
See the comments at the top of the script for the QEMU/Buildx prerequisites.

**After the first push**, set each GHCR package's visibility to **Public** once
(GitHub → DFiantWorks → Packages → the package → Package settings → Change
visibility), otherwise `apptainer pull` will require authentication.
