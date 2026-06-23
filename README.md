# Scalapptainer

A cross-platform Scala 3 wrapper around [Apptainer](https://apptainer.org). Add it to
your build and drive Apptainer containers from Scala on **Linux, Windows and macOS**.
Scalapptainer routes every command through the right place for your OS and, on first use,
reuses an existing system Apptainer if one is on the backend's `PATH` — otherwise it installs
Apptainer for you, in **user mode** (no root).

## What is Apptainer, and why this library?

[Apptainer](https://apptainer.org) (formerly Singularity) is a Linux container runtime
built for **unprivileged, rootless** use. Unlike Docker it needs **no daemon and no root**.
A whole environment is a single, immutable `.sif` file you can run, copy, archive or check
into a release. That makes it a clean way to get a **reproducible Linux toolchain** (a
specific compiler, simulator, CLI, or GUI app) that behaves identically on every machine.

The catch is that Apptainer itself only runs on Linux, and setting it up usually means
shell scripts and a package manager. **Scalapptainer removes all of that:**

- **One dependency, three OSes.** The same Scala code runs on Linux, Windows (via WSL2)
  and macOS (via a Lima VM). Scalapptainer detects the host and routes commands to the
  right backend.
- **No root, no manual install.** If Apptainer is already on the backend's `PATH` (e.g. a
  system or setuid-root install) Scalapptainer uses it as-is; otherwise, on first use, it
  installs Apptainer for you in user mode, bundling the small helper tools the installer
  needs so even a minimal distro works.
- **A typed, fluent API.** `pull`/`build` return an immutable image handle you configure
  with [bind mounts](https://apptainer.org/docs/user/main/bind_paths_and_mounts.html),
  [env vars](https://apptainer.org/docs/user/main/environment_and_metadata.html) and X11
  forwarding, then `exec`/`run`/`shell`.

So from a few lines of Scala you can pull or build a Linux environment and run programs in
it, **including GUI apps shown on your desktop**, with reproducible results across OSes.

New to Apptainer? The official [user guide](https://apptainer.org/docs/user/main/) is the
reference; the sections most relevant here are
[Quick start](https://apptainer.org/docs/user/main/quick_start.html),
[Definition files](https://apptainer.org/docs/user/main/definition_files.html) and
[Build a container](https://apptainer.org/docs/user/main/build_a_container.html).

- **Linux**: runs `apptainer` directly on the host.
- **Windows**: runs Apptainer inside **WSL2** (via `wsl.exe`).
- **macOS**: runs Apptainer inside a **Lima** VM (via `limactl shell`).

Built with [Mill](https://mill-build.org).

## Quick taste (single-file scala scripts)

No project, no build file. Just a `.scala` file you can run with `scala run`.

**Talk to a cow.** Pulls a ready-made image and has an ASCII cow read you a random fortune.
The image is **~30 MB**; the first run downloads and converts it (**a few seconds**,
network-dependent), then caches it under `~/.scalapptainer/images` so later runs are instant.

```scala
//> using scala 3.3.8
//> using dep io.github.dfiantworks::scalapptainer:0.5.0

import scalapptainer.*

@main def cow(): Unit =
  Apptainer
    .pull("docker://ghcr.io/dfiantworks/scalapptainer-cowsay")
    .runInteractive()
```

```bash
scala run cow.scala
```

**Run a GUI app on your desktop.** Pulls a ready-made image and forwards the host display in
with `.withX11()`, so a pair of googly eyes pops up and follows your mouse around the screen.
The image is **~45 MB**; the first run downloads and converts it (**a few seconds**,
network-dependent) and caches it, so later runs start instantly.

```scala
//> using scala 3.3.8
//> using dep io.github.dfiantworks::scalapptainer:0.5.0

import scalapptainer.*

@main def eyes(): Unit =
  Apptainer
    .pull("docker://ghcr.io/dfiantworks/scalapptainer-xeyes")
    .withX11()
    .run() // googly eyes appear — close the window to finish
```

```bash
scala run eyes.scala
```

> These two images ([`docker/`](docker/)) are static and published once to GHCR; the
> `Building images` section below shows how to build your own from a definition file.

The GUI demo needs a display: WSLg on Windows, a running X server on Linux, or XQuartz on
macOS. Both scripts work unchanged on all three OSes.


## Add to your build

scala (single file or `project.scala`):

```scala
//> using dep io.github.dfiantworks::scalapptainer:0.5.0
```

mill:

```scala
def mvnDeps = Seq(mvn"io.github.dfiantworks::scalapptainer:0.5.0")
```

sbt:

```scala
libraryDependencies += "io.github.dfiantworks" %% "scalapptainer" % "0.5.0"
```

## Prerequisites

Scalapptainer never asks for root and never performs a privileged install. It does
require the Linux *environment* to already exist on non-Linux hosts (a one-time setup):

| Host | You provide | Scalapptainer provides |
|------|-------------|------------------------|
| Linux | a POSIX shell | Apptainer (system install if present, else a user-mode install) |
| Windows | **WSL2** enabled (`wsl --install`, one-time, needs admin) | Apptainer inside WSL2 |
| macOS | **Lima** installed (`brew install lima`, no admin needed) | the Lima VM (auto-provisioned from the `apptainer` template) **and** Apptainer inside it |

On each backend, Scalapptainer first looks for an `apptainer` already on the `PATH` and
uses it if found; only when none exists does it perform its own user-mode install (into
`~/.scalapptainer/<version>`). So a pre-installed system Apptainer is always preferred, and
you can override the bundled version simply by having your own on the `PATH`.

If the required backend is missing, Scalapptainer throws a `BackendUnavailableException`
with the exact commands to fix it.

> **macOS first-run setup.** The only manual step is installing Lima itself:
>
> ```bash
> brew install lima        # no admin / sudo required
> ```
>
> On first use, Scalapptainer **auto-provisions** a Lima VM named `scalapptainer` from Lima's
> bundled `apptainer` template (which installs Apptainer and configures the VM for unprivileged
> user namespaces) and starts it — printing a one-time notice, with Lima's own download/boot
> progress shown live. The first run therefore takes a few minutes while the VM image downloads;
> later runs reuse the running VM. You do **not** need to run `limactl start` or set
> `SCALAPPTAINER_LIMA_INSTANCE` yourself. To opt out and provision manually, set
> `SCALAPPTAINER_LIMA_AUTO=0` (see [Configuration](#pinned-apptainer-version)).
>
> Because the `apptainer` template ships Apptainer, macOS uses *that* Apptainer (it is on the
> VM's `PATH`) rather than a Scalapptainer user-mode install — so the pinned version below applies
> to the Linux/WSL2 backends; on macOS the Apptainer version comes from the Lima template.

> **Unprivileged user namespaces are required.** Apptainer's rootless engine runs every
> container inside an unprivileged user namespace and maps your uid/gid into it, so the backend
> must permit both. Most Linux hosts, WSL2 and properly-configured Lima VMs do; **heavily
> sandboxed containers do not** — many CI runners and online code playgrounds (e.g.
> **[Scastie](https://scastie.scala-lang.org)**) run under a seccomp/AppArmor policy that blocks
> the `unshare(CLONE_NEWUSER)` syscall, or the uid/gid mapping step, even when the kernel itself
> allows it. **Unprivileged Apptainer cannot run in such an environment** — the alternative is the
> **setuid-root** build (`apptainer-suid` on Debian/Ubuntu), which doesn't use user namespaces but
> needs root to install. Scalapptainer detects the user-namespace problem both up front (its
> user-mode install fails fast with a `UserNamespaceException` instead of installing into a backend
> where Apptainer can't run) and at run time (a container that dies with
> `Could not write info to setgroups: Permission denied` is re-reported as a `UserNamespaceException`
> with backend-specific fixes, including installing `apptainer-suid` — see
> [Troubleshooting](#troubleshooting)). (Set `SCALAPPTAINER_SKIP_USERNS_CHECK=1` only if you are
> pointing it at a setuid-root Apptainer that does not need user namespaces.)

> **You do not need to install `cpio`, `curl`, `rpm2cpio` or any other helper tools.**
> The unprivileged Apptainer installer needs them, but Scalapptainer takes care of its own
> install dependencies automatically: it **bundles static binaries** and materialises them
> into its per-user cache only when a system copy is absent (system copies always win).
> See [vendor/VENDORED-TOOLS.md](vendor/VENDORED-TOOLS.md).

## Quickstart

The recommended entry point is the `Apptainer` object itself, a ready-to-use instance
bound to the auto-detected backend (native Linux / WSL2 / Lima), so there's nothing to
construct. `pull` and `build` give you back an **`ApptainerImage`** handle, a small
immutable object that knows its own path and how to reach the backend, and you drive it
fluently:

```scala
import scalapptainer.*

// Pull a public image. The SIF is cached under ~/.scalapptainer/images and reused on
// later runs; `pull` returns a handle to it.
val alpine = Apptainer.pull("docker://alpine:latest")

// Run commands in it. `exec` returns the captured result; `.out` is its trimmed stdout.
println(alpine.exec("cat", "/etc/os-release").out)
println(alpine.exec("uname", "-a").out)

// Fluent, immutable config — bind mounts, env vars, X11 — then a terminal verb
// (exec / run / shell / inspect). Each bind/env returns a *new* handle, so `alpine`
// above is untouched.
alpine
  .bind(Apptainer.hostPath("""C:\Users\me\data"""), "/data")  // host path -> /mnt/c/... on Windows
  .env("GREETING" -> "hi")
  .exec("sh", "-c", "echo $GREETING; ls /data")
```

### Building images

`build` accepts a
[definition file](https://apptainer.org/docs/user/main/definition_files.html) as a
**path**, a **classpath resource** (a bare name is looked up on the JVM classpath first,
handy for `.def` files packaged with your app), or **inline contents**. Building a def file
runs its [`%post`](https://apptainer.org/docs/user/main/definition_files.html#post) as
root; `enableNonRootBuild` does that
[unprivileged](https://apptainer.org/docs/user/main/fakeroot.html) (via a user namespace),
so no `sudo` is needed:

```scala
// Inline definition file — built unprivileged, cached as ~/.scalapptainer/images/figlet.sif.
val figlet = Apptainer.build(
  """Bootstrap: docker
    |From: ubuntu:24.04
    |%post
    |    apt-get update && apt-get install -y --no-install-recommends figlet
    |""".stripMargin,
  name = "figlet",
  enableNonRootBuild = true
)
println(figlet.exec("figlet", "Scalapptainer").out)

Apptainer.build("path/to/app.def", name = "app")  // a packaged resource, else a file path
```

Already-built images are reused unless you pass `force = true`. To show a **GUI** app on
your desktop, build (or pull) an image that has one and forward the host display with
`.withX11()`. It adapts per backend (WSLg on Windows, the X11 socket on Linux, XQuartz
over TCP on macOS/Lima):

```scala
val gui = Apptainer.build(
  "Bootstrap: docker\nFrom: ubuntu:24.04\n%post\n    apt-get update && apt-get install -y x11-apps\n",
  name = "xclock",
  enableNonRootBuild = true
)
gui.withX11().run("xclock")   // a clock window opens on your desktop
```

### Low-level escape hatch

Under the handle API is a thin core: pass any `apptainer` argv straight through, or build
the typed command objects yourself.

```scala
import scalapptainer.commands.*

println(Apptainer.version)                 // "apptainer version 1.x.y"
Apptainer.exec(Seq("--version"))           // raw argv
Apptainer.run(RunCommand("img.sif").withOptions(_.cleanEnv().bind("/data", "/data")))
Apptainer.execIn("img.sif", "cat", "/etc/os-release")  // string-based convenience
```

The backend prerequisite check and the user-mode Apptainer install happen lazily on
first actual use, so simply referencing `Apptainer` touches nothing on disk.

### Custom configuration

For a specific WSL2 distro / Lima instance or an injected runner (e.g. tests), build
your own instance instead of using the default object:

```scala
val custom = Apptainer(config = BackendConfig(wslDistro = Some("Ubuntu-22.04")))
custom.run(RunCommand("img.sif"))

val forTest = Apptainer.forBackend(myBackend)    // bind an explicit backend
```

### Host paths

When passing host paths (for bind mounts or image locations), translate them to the
backend's view first. This is a no-op on Linux/macOS and maps `C:\...` to `/mnt/c/...`
on Windows:

```scala
val guestPath = Apptainer.hostPath("""C:\Users\me\data""") // -> /mnt/c/Users/me/data
Apptainer.image("img.sif").bind(guestPath, "/data").run()
```

## Demos

The `examples` module has three small, self-contained demos. Run them with Mill from the
repo root:

| Demo | What it shows | Run |
|------|---------------|-----|
| `demo.HelloDemo` | pull a tiny image and run commands in it | `./mill examples.runMain demo.HelloDemo` |
| `demo.BuildDemo` | build a minimal image from an inline def (unprivileged), then bind-mount a generated file back out to the host | `./mill examples.runMain demo.BuildDemo` |
| `demo.GuiDemo` | run a GUI app (`xclock`) with the host display forwarded in via `.withX11()` | `./mill examples.runMain demo.GuiDemo` |

`./mill examples.run` also runs the default (`HelloDemo`). The first run of each builds or pulls
its image (cached afterwards). `GuiDemo` needs a display (WSLg on Windows, a running X
server on Linux, or XQuartz on macOS) and opens a clock window you close to finish. The
demo sources under [examples/src/demo/](examples/src/demo/) are short and commented as a
worked tour of the API.

## How it works

```
Apptainer (entry point)
  ├── pull / build       → ApptainerImage   (handle: bind/env/withX11 → exec/run/shell/inspect)
  │                         images cached under ~/.scalapptainer/images
  ├── Backend            detect host → LinuxBackend | Wsl2Backend | LimaBackend
  │     ├── wrapApptainer / runShell   (route argv into the backend)
  │     ├── translatePath              (host → guest paths)
  │     ├── x11Forwarding              (backend-aware display: socket / XQuartz-over-TCP)
  │     └── checkAvailable             (prerequisite probe + install instructions)
  ├── ApptainerInstaller resolve-or-install the pinned apptainer in user mode (cached)
  │     └── VendoredTools  ensure curl/rpm2cpio/cpio (system-first, vendored fallback)
  └── commands.*         typed DSL → apptainer argv  (the thin `exec` runs it)
```

### Pinned Apptainer version

Each Scalapptainer release pins a specific Apptainer version (currently **1.5.1**). The
user-mode install fetches exactly that release (`install-unprivileged.sh -v <version>`)
and places it under `~/.scalapptainer/<version>/` inside the backend, so bumping the pin
(e.g. to pick up an upstream fix) installs cleanly alongside the old one rather than
overwriting it. A system-wide `apptainer` already on the backend PATH still wins.

Configuration via environment variables:

- `SCALAPPTAINER_WSL_DISTRO`: target a specific WSL2 distro (default: the WSL default).
- `SCALAPPTAINER_LIMA_INSTANCE`: the Lima instance to use / auto-provision (default: `scalapptainer`).
- `SCALAPPTAINER_LIMA_VM_TYPE`: VM type passed to `limactl start` when auto-provisioning (default: Lima's own
  default; set `qemu` where `vz` cannot boot, e.g. an Intel host without nested virtualization).
- `SCALAPPTAINER_LIMA_AUTO`: set to `0` to disable macOS auto-provisioning and get manual instructions instead.
- `SCALAPPTAINER_APPTAINER_VERSION`: override the pinned Apptainer version to install (Linux/WSL2 backends).
- `SCALAPPTAINER_INSTALLER_URL`: override the unprivileged installer script URL
  (default: the `install-unprivileged.sh` from the pinned release's tag).

## Troubleshooting

### `Could not write info to setgroups: Permission denied`

Usually followed by `Error while waiting event for user namespace mappings: no event received`.
Apptainer's rootless engine could create a user namespace but was **blocked from writing its
uid/gid mapping** — it is not a problem with your image or code. Scalapptainer re-reports this as
a `UserNamespaceException` with the fix for your backend:

- **macOS (Lima).** Scalapptainer auto-provisions its `scalapptainer` VM from the `apptainer`
  template, which is configured correctly — so you only hit this if you pointed
  `SCALAPPTAINER_LIMA_INSTANCE` at a custom VM (e.g. a plain `default`) that lacks the config.
  Either unset it to use the auto-provisioned VM, or recreate yours from the template:
  ```bash
  limactl stop <instance> && limactl delete <instance>
  limactl start --name=<instance> template:apptainer
  ```
- **Linux.** The host (or the container/VM you are in) restricts unprivileged user namespaces.
  The simplest fix (no system-wide security change) is to install the **setuid-root** build of
  Apptainer, which doesn't use user namespaces at all — Scalapptainer then uses it from `PATH`.
  On Debian/Ubuntu it lives in the Apptainer PPA, so add that first:
  ```bash
  sudo add-apt-repository -y ppa:apptainer/ppa
  sudo apt-get update && sudo apt-get install -y apptainer-suid
  # RPM distros: install the apptainer-suid package from EPEL or the Apptainer repo
  ```
  Or re-enable unprivileged user namespaces instead:
  ```bash
  # Ubuntu 23.10+/24.04 (AppArmor restriction):
  sudo sysctl -w kernel.apparmor_restrict_unprivileged_userns=0
  # Older Debian/RHEL kernels:
  sudo sysctl -w kernel.unprivileged_userns_clone=1
  sudo sysctl -w user.max_user_namespaces=15000
  ```
  If you are running **inside another container** (Docker/Podman/CI), launch it so it can nest
  user namespaces (keep `CAP_SETUID`/`CAP_SETGID`, avoid a `setgroups`-restricting seccomp
  profile) or run on a real host/VM. Locked-down playgrounds such as Scastie block user
  namespaces with no unprivileged workaround — there `apptainer-suid` is the only option.
- **Windows (WSL2).** Unusual — confirm the distro is WSL2, not WSL1, with `wsl -l -v` (the
  `VERSION` column must read `2`).

## Building from source

```bash
./mill scalapptainer.compile     # compile the library
./mill scalapptainer.test        # run the unit tests
./mill scalapptainer.publishLocal
```

The unit tests inject a recording `CommandRunner`, so they exercise all backend,
installer and argument-building logic without needing a real WSL2/Lima/Apptainer.

To refresh the bundled static tool binaries: `vendor/refresh-tools.sh`.

## License

Apache-2.0. Bundled third-party tool binaries retain their own licenses; see
[vendor/VENDORED-TOOLS.md](vendor/VENDORED-TOOLS.md).
