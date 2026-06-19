# Scalapptainer

A cross-platform Scala 3 wrapper around [Apptainer](https://apptainer.org). Add it to
your build and drive Apptainer containers from Scala on **Linux, Windows and macOS** —
Scalapptainer routes every command through the right place for your OS and installs
Apptainer for you, in **user mode** (no root), on first use.

- **Linux** — runs `apptainer` directly on the host.
- **Windows** — runs Apptainer inside **WSL2** (via `wsl.exe`).
- **macOS** — runs Apptainer inside a **Lima** VM (via `limactl shell`).

Built with [Mill](https://mill-build.org).

## Prerequisites

Scalapptainer never asks for root and never performs a privileged install. It does
require the Linux *environment* to already exist on non-Linux hosts (a one-time setup):

| Host | You provide | Scalapptainer provides |
|------|-------------|------------------------|
| Linux | a POSIX shell | Apptainer (user-mode install) |
| Windows | **WSL2** enabled (`wsl --install`, one-time, needs admin) | Apptainer inside WSL2 |
| macOS | **Lima** installed and an instance running (`brew install lima && limactl start`) | Apptainer inside the VM |

If the required backend is missing, Scalapptainer throws a `BackendUnavailableException`
with the exact commands to fix it.

The unprivileged Apptainer installer needs `curl`, `rpm2cpio` and `cpio` inside the
backend. Minimal distros usually lack `rpm2cpio` (and sometimes the others), and
installing them would need root — so Scalapptainer **bundles static binaries** of these
tools and materialises them into its per-user cache only when the system copy is absent.
See [vendor/VENDORED-TOOLS.md](vendor/VENDORED-TOOLS.md).

## Add to your build

Mill:

```scala
def mvnDeps = Seq(mvn"works.dfiant::scalapptainer:0.1.0-SNAPSHOT")
```

sbt:

```scala
libraryDependencies += "works.dfiant" %% "scalapptainer" % "0.1.0-SNAPSHOT"
```

## Quickstart

```scala
import scalapptainer.*
import scalapptainer.commands.*

// Auto-detects the backend (native Linux / WSL2 / Lima) for the current host.
val apptainer = Apptainer()

// Thin escape hatch: pass any apptainer arguments through.
println(apptainer.version)                       // "apptainer version 1.x.y"
val res = apptainer.exec(Seq("--version"))
println(res.out)

// Typed DSL — build commands, then run them.
apptainer.run(
  RunCommand("docker://alpine:latest", appArgs = Seq("echo", "hello"))
    .withOptions(_.cleanEnv().bind("/data", "/data"))
)

// Convenience wrappers.
apptainer.pull("docker://alpine:latest", dest = Some("alpine.sif"))
apptainer.execIn("alpine.sif", "cat", "/etc/os-release")
apptainer.inspect("alpine.sif")

// Interactive shell (inherits your terminal's stdio); returns the exit code.
val code = apptainer.shell("alpine.sif", ExecOptions().contain())
```

### Host paths

When passing host paths (for bind mounts or image locations), translate them to the
backend's view first — this is a no-op on Linux/macOS and maps `C:\...` to `/mnt/c/...`
on Windows:

```scala
val guestPath = apptainer.hostPath("""C:\Users\me\data""") // -> /mnt/c/Users/me/data
apptainer.run(RunCommand("img.sif").withOptions(_.bind(guestPath, "/data")))
```

## How it works

```
Apptainer (entry point)
  ├── Backend            detect host → LinuxBackend | Wsl2Backend | LimaBackend
  │     ├── wrapApptainer / runShell   (route argv into the backend)
  │     ├── translatePath              (host → guest paths)
  │     └── checkAvailable             (prerequisite probe + install instructions)
  ├── ApptainerInstaller resolve-or-install apptainer in user mode (cached)
  │     └── VendoredTools  ensure curl/rpm2cpio/cpio (system-first, vendored fallback)
  └── commands.*         typed DSL → apptainer argv  (the thin `exec` runs it)
```

Configuration via environment variables:

- `SCALAPPTAINER_WSL_DISTRO` — target a specific WSL2 distro (default: the WSL default).
- `SCALAPPTAINER_LIMA_INSTANCE` — target a specific Lima instance (default: `default`).
- `SCALAPPTAINER_INSTALLER_URL` — override the unprivileged installer script URL
  (e.g. to pin a specific Apptainer release).

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

Apache-2.0. Bundled third-party tool binaries retain their own licenses — see
[vendor/VENDORED-TOOLS.md](vendor/VENDORED-TOOLS.md).
