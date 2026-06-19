# Scalapptainer intro-demo image: xeyes, a pair of googly eyes that follow the
# mouse. Published once to ghcr.io/dfiantworks/scalapptainer-xeyes (see ../README.md).
FROM ubuntu:24.04

RUN apt-get update \
 && apt-get install -y --no-install-recommends x11-apps \
 && rm -rf /var/lib/apt/lists/*

# `apptainer run <image>` (with no args) executes this; X11 forwarding is added
# by ApptainerImage.withX11() at run time.
CMD ["xeyes"]
