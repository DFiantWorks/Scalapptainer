# Scalapptainer intro-demo image: an ASCII cow that speaks a random fortune.
# Published once to ghcr.io/dfiantworks/scalapptainer-cowsay (see ../README.md).
FROM ubuntu:24.04

RUN apt-get update \
 && apt-get install -y --no-install-recommends cowsay fortune-mod fortunes \
 && rm -rf /var/lib/apt/lists/*

# cowsay/fortune install under /usr/games. Put it on PATH so the tools are
# callable by name, and add a one-word convenience command, `moo`.
ENV PATH="/usr/games:${PATH}"
RUN printf '#!/bin/sh\nfortune | cowsay\n' > /usr/local/bin/moo \
 && chmod +x /usr/local/bin/moo

# `apptainer run <image>` (with no args) executes this.
CMD ["moo"]
