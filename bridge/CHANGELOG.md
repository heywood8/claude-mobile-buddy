# Changelog

## [0.7.0](https://github.com/heywood8/claude-mobile-buddy/compare/bridge-v0.6.0...bridge-v0.7.0) (2026-08-28)


### Features

* **protocol:** a shared clipboard between the Mac and the phone ([#9](https://github.com/heywood8/claude-mobile-buddy/issues/9)) ([1132b86](https://github.com/heywood8/claude-mobile-buddy/commit/1132b86b564c288b60899efb4cc86109669d356b))

## [0.6.0](https://github.com/heywood8/claude-mobile-buddy/compare/bridge-v0.5.0...bridge-v0.6.0) (2026-08-27)


### Features

* **bridge:** forget a terminal that never said goodbye ([#6](https://github.com/heywood8/claude-mobile-buddy/issues/6)) ([c84e814](https://github.com/heywood8/claude-mobile-buddy/commit/c84e8144c82f0dec234328b7ee9877965b4755dd))
* **bridge:** say which hooks are actually installed ([5c9dcad](https://github.com/heywood8/claude-mobile-buddy/commit/5c9dcad9f5fe6deba70e0b6ca19181b75c800ddc))
* **bridge:** send the reason a call is being made, not just the command ([21873b0](https://github.com/heywood8/claude-mobile-buddy/commit/21873b03c06ef8d2c6f51334df9ea1a52221b135))
* **bridge:** tell the phone what the terminal decided ([5fef88c](https://github.com/heywood8/claude-mobile-buddy/commit/5fef88caba7dbeca33a0f4205050530653fe4ec6))
* count the tokens each session has spent ([8aa5e69](https://github.com/heywood8/claude-mobile-buddy/commit/8aa5e69f25b272b8d707a57bc8bf4424300f53c9))
* four more moods, and shorter paths ([be17795](https://github.com/heywood8/claude-mobile-buddy/commit/be17795c09f9015ca12477b334bc2da2511854ed))
* several requests at once, and a crab you can poke ([3223b54](https://github.com/heywood8/claude-mobile-buddy/commit/3223b54c39691302243e80a03491d48b858bc183))
* the question comes from the session that is asking ([eafe218](https://github.com/heywood8/claude-mobile-buddy/commit/eafe21865556f997d5220f6d16582b3d298d5c6c))


### Bug Fixes

* **bridge:** answer the waiting hooks before exiting ([08e6a55](https://github.com/heywood8/claude-mobile-buddy/commit/08e6a5528902417cd06beea1f33ed16919af095a))
* **bridge:** do not hand the queue to the terminal over a flicker ([41b7384](https://github.com/heywood8/claude-mobile-buddy/commit/41b7384ae41d1649176ed00040f8aea0a09468a4))
* **bridge:** drop a request the terminal has already answered ([7515878](https://github.com/heywood8/claude-mobile-buddy/commit/7515878bed05f90f9c3182b4979d92d5d5a55eb0))
* **bridge:** install every hook, not the four it used to know ([80c2a0a](https://github.com/heywood8/claude-mobile-buddy/commit/80c2a0a46391d601458eca91abe63fd183058374))
* **bridge:** let go of a peripheral the controller took away ([31afd41](https://github.com/heywood8/claude-mobile-buddy/commit/31afd41d3ac7599e8fb17664090f5e75dd3d90c5))
* count new tokens, and count them once ([2eab1bd](https://github.com/heywood8/claude-mobile-buddy/commit/2eab1bd68ea8d969285913abd85f70b057c9563d))

## [0.5.0](https://github.com/heywood8/claude-mobile-buddy/compare/bridge-v0.4.0...bridge-v0.5.0) (2026-08-26)


### Features

* **bridge:** install the bundle where launchd can keep finding it ([f13abe3](https://github.com/heywood8/claude-mobile-buddy/commit/f13abe3d1e7e3a89f89472b26a28c8ee3def770e))
* **bridge:** name the bundle Claude Buddy (bridge) ([ebaf815](https://github.com/heywood8/claude-mobile-buddy/commit/ebaf815a95e18fb91b5e558647c8bd2dc4c834bf))

## [0.4.0](https://github.com/heywood8/claude-mobile-buddy/compare/bridge-v0.3.0...bridge-v0.4.0) (2026-08-25)


### Features

* **bridge:** log the first sight of each session ([7d1c7b6](https://github.com/heywood8/claude-mobile-buddy/commit/7d1c7b6864ba0691c421c96281c3ae0672b6146f))
* show which sessions are running and how long since you last stepped in ([4569127](https://github.com/heywood8/claude-mobile-buddy/commit/45691277fd3589946d0814c64028583acff116b5))

## [0.3.0](https://github.com/heywood8/claude-mobile-buddy/compare/bridge-v0.2.0...bridge-v0.3.0) (2026-08-25)


### Features

* **bridge:** keep prompts that cannot be answered remotely off the phone ([b48b220](https://github.com/heywood8/claude-mobile-buddy/commit/b48b2207e251bc925337a9a2c3cd43a420ad9618))
* **bridge:** merge the hooks in, with the diff and a prompt ([efad59f](https://github.com/heywood8/claude-mobile-buddy/commit/efad59f29cab0d3ec49eca188328917bdeef7391))
* **bridge:** pair from an image instead of terminal glyphs ([f364cb8](https://github.com/heywood8/claude-mobile-buddy/commit/f364cb8b9a5255f61cc09287ab709dbb84623c48))
* encrypt the live channel and pair by scanning ([b9e3320](https://github.com/heywood8/claude-mobile-buddy/commit/b9e332030043cc2e6737be0262777799da81ee25))


### Bug Fixes

* **bridge:** diff the settings merge properly ([f6faad9](https://github.com/heywood8/claude-mobile-buddy/commit/f6faad92d93172f615195db237b1dc87cc4ceb93))
* **bridge:** notice when the phone goes away ([220dad0](https://github.com/heywood8/claude-mobile-buddy/commit/220dad0f1c10bc66e5faaeef8271e5f8c2026942))
* **bridge:** render the pairing code with full blocks ([27488f9](https://github.com/heywood8/claude-mobile-buddy/commit/27488f92f9057ed8bc29313acd2358091f04e8cc))
* drop a request whose caller hung up, and stop buzzing over an open window ([a0907a5](https://github.com/heywood8/claude-mobile-buddy/commit/a0907a580d58a5bb5ff10590ad673fe84f737ea2))

## [0.2.0](https://github.com/heywood8/claude-mobile-buddy/compare/bridge-v0.1.0...bridge-v0.2.0) (2026-08-25)


### Features

* approval queue, pairing identity and terminal QR code ([4dcb6da](https://github.com/heywood8/claude-mobile-buddy/commit/4dcb6da6541f90ba73cd12b912d5a0784da3cdd0))
* handshake state machines on both sides ([af7d064](https://github.com/heywood8/claude-mobile-buddy/commit/af7d0646723b39b1ba74de50f9ac556ea3b88cef))
* session crypto on both sides, against shared test vectors ([86600dd](https://github.com/heywood8/claude-mobile-buddy/commit/86600dd58740fcefbe17aeb2b849430ecb318ddc))
* walking skeleton for both halves of the link ([796a5b6](https://github.com/heywood8/claude-mobile-buddy/commit/796a5b693c981f144ba7c61af668603b56945016))
