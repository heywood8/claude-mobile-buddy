# Changelog

## [0.3.0](https://github.com/heywood8/claude-mobile-buddy/compare/android-v0.2.0...android-v0.3.0) (2026-08-25)


### Features

* **android:** follow the system theme, and offer to hold the screen awake ([9718c56](https://github.com/heywood8/claude-mobile-buddy/commit/9718c56da916b81898b2e67ac07455af76d23f6e))
* **android:** journal every decision, and every one nobody made ([005f82f](https://github.com/heywood8/claude-mobile-buddy/commit/005f82f25f59239d9932981d12c6989f54f26d2c))
* encrypt the live channel and pair by scanning ([b9e3320](https://github.com/heywood8/claude-mobile-buddy/commit/b9e332030043cc2e6737be0262777799da81ee25))


### Bug Fixes

* **android:** serialise the GATT transport onto one thread ([e8bc9af](https://github.com/heywood8/claude-mobile-buddy/commit/e8bc9af303744d26e5b6512ad950a0cd3c0c0650))
* **ci:** publish a signed release build, not a debuggable one ([0348f59](https://github.com/heywood8/claude-mobile-buddy/commit/0348f592ffc87059729f4cb1a363e98e7054a7e5))
* drop a request whose caller hung up, and stop buzzing over an open window ([a0907a5](https://github.com/heywood8/claude-mobile-buddy/commit/a0907a580d58a5bb5ff10590ad673fe84f737ea2))

## [0.2.0](https://github.com/heywood8/claude-mobile-buddy/compare/android-v0.1.0...android-v0.2.0) (2026-08-25)


### Features

* handshake state machines on both sides ([af7d064](https://github.com/heywood8/claude-mobile-buddy/commit/af7d0646723b39b1ba74de50f9ac556ea3b88cef))
* session crypto on both sides, against shared test vectors ([86600dd](https://github.com/heywood8/claude-mobile-buddy/commit/86600dd58740fcefbe17aeb2b849430ecb318ddc))
* walking skeleton for both halves of the link ([796a5b6](https://github.com/heywood8/claude-mobile-buddy/commit/796a5b693c981f144ba7c61af668603b56945016))


### Bug Fixes

* **android:** make intent targets explicit in the source ([ca63d9a](https://github.com/heywood8/claude-mobile-buddy/commit/ca63d9a99457ac3517a224aa3c938067a8168e57))
