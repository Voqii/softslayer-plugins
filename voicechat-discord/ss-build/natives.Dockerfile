# Rust + zig (for an old-glibc .so, like upstream's CI) + cmake (libopus is built from source).
FROM ghcr.io/rust-cross/cargo-zigbuild:latest
RUN apt-get update && apt-get install -y --no-install-recommends cmake && rm -rf /var/lib/apt/lists/*
