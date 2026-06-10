# Build patches

These patches must be applied to the upstream repositories before building
the native static libraries.

## Repositories

| Repo | Remote | Base commit |
|------|--------|-------------|
| xmrwallet | https://github.com/m2049r/xmrwallet | `41a7b7b` |
| monero | https://github.com/m2049r/monero | `51eff04e9` |

The monero repo is a submodule inside xmrwallet at `external-libs/monero`.

## Patches

### xmrwallet/

| File | Description |
|------|-------------|
| `0001-Docker-and-makefile-patch.patch` | Pin base image to `debian:bullseye`, fix build tool names, update Boost/libiconv download URLs, fix libiconv cross-compile toolchain, add `mobile` Makefile target |
| `0002-Specify-linux-amd64-platform-for-Docker-builds.patch` | Add `--platform linux/amd64` to all `docker build` calls — required for cross-compilation on Apple Silicon Macs |

### monero/

| File | Description |
|------|-------------|
| `0001-Add-generateKey-and-generateAddress.patch` | Adds `Wallet::generateKey` and `Wallet::generateAddress` static functions to `src/wallet/api/wallet.cpp` and `wallet2_api.h` — derives keys and addresses from a mnemonic without opening a wallet file |

## How to apply

```sh
# Clone xmrwallet at the known base commit
git clone https://github.com/m2049r/xmrwallet.git
cd xmrwallet
git checkout 41a7b7b

# Initialize the monero submodule
git submodule update --init external-libs/monero

# Check out the monero base commit
git -C external-libs/monero checkout 51eff04e9

# Apply xmrwallet patches
git am < ../patches/xmrwallet/0001-Docker-and-makefile-patch.patch
git am < ../patches/xmrwallet/0002-Specify-linux-amd64-platform-for-Docker-builds.patch

# Apply monero patch
git -C external-libs/monero am < ../patches/monero/0001-Add-generateKey-and-generateAddress.patch

# Build
cd external-libs
make mobile   # arm64-v8a only (recommended for development)
# or: make all   # arm64-v8a + armeabi-v7a + x86_64
```

After the build completes, copy the output from `external-libs/arm64-v8a/` (and
other ABI directories) into `monerokit/external-libs/` in this project.
