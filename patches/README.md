# Build patches

These patches must be applied to the upstream repositories before building
the native static libraries.

## Repositories

| Repo | Remote | Base commit |
|------|--------|-------------|
| xmrwallet | https://github.com/m2049r/xmrwallet | `41a7b7b` |
| monero | https://github.com/monero-project/monero | `85f036ba7` (release-v0.18 head, 2026-10-14) |

The xmrwallet build expects the monero source tree at `external-libs/monero`
(the Docker build context). It is a plain clone, not a submodule.

## Patches

### xmrwallet/

| File | Description |
|------|-------------|
| `0001-Docker-and-makefile-patch.patch` | Pin base image to `debian:bullseye`, fix build tool names, update Boost/libiconv download URLs, fix libiconv cross-compile toolchain, add `mobile` Makefile target |
| `0002-Specify-linux-amd64-platform-for-Docker-builds.patch` | Add `--platform linux/amd64` to all `docker build` calls — required for cross-compilation on Apple Silicon Macs |
| `0003-Update-package-zlib-and-libsodium-download-sources.patch` | Fetch bullseye apt packages from `archive.debian.org` and zlib from its GitHub release page, check out libsodium by its pinned commit (the `1.0.18` tag is gone), and skip its `config.sub`/`config.guess` download from savannah |

### monero/

Apply in numeric order on top of upstream `85f036ba7`.

0001–0003 are the monerujo mobile commits from the m2049r/monero fork
(previously the kit built from that fork directly).

| File | Description |
|------|-------------|
| `0001-monerujo-patches.patch` | Monerujo build setup: `.dockerignore`, `MONERUJO_HIDAPI` CMake option, Android wallet_api Makefile targets, Ledger over Monerujo USB I/O, `Coins`/`CoinsInfo` wallet API, extra `Wallet` API methods |
| `0002-support-for-Monerujo-Sidekick.patch` | Sidekick hardware device (`device_sidekick`), Bluetooth device I/O, `MONERUJO` CMake option |
| `0003-remove-extra-whitespace.patch` | Whitespace cleanup in `device_sidekick.cpp` |

0004–0010 are upstream monero master commits to the epee HTTP client
(`contrib/epee/include/net/http_client.h`) not yet on release-v0.18.

| File | Description |
|------|-------------|
| `0004-epee-reject-malformed-HTTP-status-lines.patch` | Reject malformed HTTP status lines and disconnect on wrong receive state |
| `0005-epee-bound-HTTP-client-response-headers.patch` | Cap response header size and avoid rescanning the whole header cache |
| `0006-epee-avoid-quadratic-chunk-size-parsing-and-unbounde.patch` | Linear chunk-size parsing and bounded receive deadlines |
| `0007-epee-correct-body-read-error-states-and-connection-c.patch` | Use the error state on body decode failures and honour `Connection: close` |
| `0008-epee-reject-excess-bytes-in-fixed-length-HTTP-respon.patch` | Reject bodies longer than `Content-Length` |
| `0009-epee-reset-http-client-state-after-receive-exception.patch` | Reset the receive state machine when receiving throws |
| `0010-epee-bound-and-reset-chunked-response-cache.patch` | Cap chunked-transfer metadata and reset the chunked cache between responses |

0011–0013 are this kit's own changes.

| File | Description |
|------|-------------|
| `0011-Add-generateKey-and-generateAddress.patch` | Adds `Wallet::generateKey` and `Wallet::generateAddress` static functions to `src/wallet/api/wallet.cpp` and `wallet2_api.h` — derives keys and addresses from a mnemonic without opening a wallet file |
| `0012-Add-storeBlocking-thread-safe-store.patch` | Adds `Wallet::storeBlocking`, a `store` that takes the refresh mutex so it cannot run concurrently with a refresh |
| `0013-Unwrap-chacha_key-before-hex-formatting-it-in-device.patch` | Hex-format `crypto::chacha_key` in `device_sidekick.cpp` through its underlying array, since `epee::as_byte_span` now rejects non-trivially-copyable types |

## How to apply

```sh
# Clone xmrwallet at the known base commit
git clone https://github.com/m2049r/xmrwallet.git
cd xmrwallet
git checkout 41a7b7b

# Apply xmrwallet patches
git am ../patches/xmrwallet/0001-Docker-and-makefile-patch.patch
git am ../patches/xmrwallet/0002-Specify-linux-amd64-platform-for-Docker-builds.patch
git am ../patches/xmrwallet/0003-Update-package-zlib-and-libsodium-download-sources.patch

# Clone upstream monero at the base commit into the build context
git clone https://github.com/monero-project/monero.git external-libs/monero
git -C external-libs/monero checkout 85f036ba7
git -C external-libs/monero submodule update --init --recursive

# Apply the thirteen monero patches in order
git -C external-libs/monero am ../../../patches/monero/00*.patch

# Build
cd external-libs
make mobile NPROC=12   # arm64-v8a only (recommended for development)
# or: make all NPROC=12   # arm64-v8a + armeabi-v7a + x86_64
```

`NPROC` is the `make -j` parallelism inside the Docker build; set it to the
CPU count given to Docker.

After the build completes, copy the output from `external-libs/arm64-v8a/` (and
other ABI directories) into `monerokit/external-libs/` in this project, and
`external-libs/include/wallet2_api.h` over
`monerokit/external-libs/include/wallet2_api.h`.
