# Dockerfile: the router as something a platform can run.
#
# History
#   2026-08-09  A. Sigdel  Created with #537.
#
# Two stages, so the image that runs carries a binary and not a Rust toolchain.
# The builder is ~1.5GB and the runtime is a Debian slim with libssl and CA
# certificates, which is what the upstream client needs to make an HTTPS request
# and nothing more.
#
# --no-default-features is the decision worth reading. The default feature is
# `onnx`, and the ONNX embedder exists to score a prompt's difficulty. This
# deployment does not score: the phone routes and sends the tier it chose,
# classify.rs reads it from the model field and policy.rs honours it as
# Reason::Pinned. So the embedder would be built, downloaded for, and never
# consulted -- and it is the largest thing in the image by a wide margin.
#
# The scoring path is not removed and is still tested; it is simply not on this
# deployment's route. A laptop running the agent against this server without
# pinning still gets a routed request, served by the hash embedder.

# 1.95.0, matching .github/actions/rust-setup and router/Cargo.toml's
# rust-version. Pinned rather than `rust:slim`, for the reason that action gives:
# a toolchain that moves underneath turns an unrelated pull request red. The
# image job caught this too -- 1.90 built nothing, because edition 2024 and the
# declared rust-version are both newer than it.
FROM rust:1.95.0-slim-bookworm AS builder

# pkg-config and libssl-dev for the TLS the upstream client links. Nothing else:
# git and memory are off, so libgit2 and SQLite are not built.
RUN apt-get update \
    && apt-get install --no-install-recommends -y pkg-config libssl-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build

# The manifest first, so a change to source does not re-download the index. The
# stubs are what make that layer cacheable: cargo needs something to build.
COPY router/Cargo.toml router/Cargo.lock ./

# Every declared target, not just the binary. Cargo validates the whole manifest
# before it builds anything, so a [[bench]] with no file is a hard error -- which
# is how this first failed in CI, with `can't find \`decide\` bench`. lib.rs
# matters too: [lib] is declared, and the binary depends on it.
RUN mkdir -p src benches \
    && echo 'fn main() {}' > src/main.rs \
    && touch src/lib.rs \
    && for bench in decide serve load; do echo 'fn main() {}' > "benches/$bench.rs"; done \
    && cargo build --release --no-default-features --bin wattrouter \
    && rm -rf src benches

COPY router/src ./src
COPY router/benches ./benches

# Touched so cargo rebuilds them: the stubs above left artefacts newer than the
# source that replaced them, and cargo compares timestamps.
RUN touch src/main.rs src/lib.rs \
    && cargo build --release --no-default-features --bin wattrouter

FROM debian:bookworm-slim

RUN apt-get update \
    && apt-get install --no-install-recommends -y ca-certificates libssl3 \
    && rm -rf /var/lib/apt/lists/*

# Unprivileged. The process binds a port above 1024 and reads its configuration
# from the environment, so it needs nothing root is for.
RUN useradd --system --create-home --uid 10001 wattrouter
USER wattrouter

COPY --from=builder /build/target/release/wattrouter /usr/local/bin/wattrouter

# 0.0.0.0 rather than the 127.0.0.1 default, and this is the line that would
# otherwise cost an afternoon: loopback inside a container is the container, so
# a health check from outside times out and the deployment is marked failed with
# a process running perfectly. The default stays loopback because a router that
# binds every interface by default is the mistake #533 was about; a container is
# the place to say otherwise.
#
# PORT is what a platform assigns. The default matches the crate's own.
ENV WATTROUTER_ADDR=0.0.0.0:8080
EXPOSE 8080

CMD ["wattrouter"]
