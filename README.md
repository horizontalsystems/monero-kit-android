# monero-kit-android

Android SDK for Monero wallet integration. Provides a Kotlin/Java API over the Monero C++ wallet library via JNI.

## Requirements

- Android API 21+
- ABI: arm64-v8a, armeabi-v7a, x86_64

## Installation

Add the module as a dependency in your `settings.gradle` and `build.gradle`.

## Usage

```kotlin
// TODO: add usage example
```

## Building native libraries

The native `.a` libraries bundled in `monerokit/external-libs/` are compiled from
Monero core using the build scripts from [xmrwallet](https://github.com/m2049r/xmrwallet).
The Monero core source includes a patch to `src/wallet/wallet.cpp` that adds
`generateKey` and `generateAddress` functions.

## License

Copyright (c) 2025 Horizontal Systems <hello@horizontalsystems.io>

This project is licensed under the MIT License — see [LICENSE](LICENSE) for details.

This project includes third-party components under Apache 2.0 and BSD 3-Clause licenses.
See [NOTICE](NOTICE) for full attribution and license texts.

## Third-party acknowledgements

**[xmrwallet (monerujo)](https://github.com/m2049r/xmrwallet)** by m2049r  
Copyright (c) 2017-2024 m2049r  
Licensed under the Apache License, Version 2.0  
JNI bridge (`monerujo.cpp`, `monerujo.h`) and wallet model classes are derived from this project.

**[The Monero Project](https://github.com/monero-project/monero)**  
Copyright (c) 2014-2022, The Monero Project  
Licensed under the BSD 3-Clause License  
Native wallet libraries are compiled from Monero core source.

**[NetCipher](https://github.com/guardianproject/NetCipher)** by The Guardian Project  
Copyright 2012-2016 Nathan Freitas, 2014-2016 Hans-Christoph Steiner, 2015 str4d, portions 2016 CommonsWare  
Licensed under the Apache License, Version 2.0

**Android Open Source Project**  
Copyright (C) 2014 The Android Open Source Project  
Licensed under the Apache License, Version 2.0
