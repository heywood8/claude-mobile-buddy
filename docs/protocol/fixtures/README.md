# Protocol test vectors

`vectors.json` is the shared contract between the two implementations. The Swift bridge and the
Kotlin app both run unit tests against this one file, so neither can drift without CI noticing.

Two independent implementations of the same spec always diverge eventually — the only question
is whether you find out in CI or in the air, holding a phone that has gone quiet for no visible
reason.

## Provenance

The vectors were **not** produced by either implementation. Deriving them from one side would
make that side its own reference, and a bug in it would become the specification. They come from
a third implementation written against RFC 5869 and the AES-GCM parameters in `../../PROTOCOL.md`
using Node's built-in `crypto` — `hkdfSync` and `aes-256-gcm`.

Inputs are fixed byte ranges (`0x00..0x1f` for the pre-shared key, `0x20..0x3f` for the host
salt, and so on) rather than random values, so the file is reproducible and reviewable. This is
test data. It is never live key material, and the key ranges are chosen to be obviously synthetic.

## Regenerating

Only when the protocol itself changes, which means `PROTOCOL.md` changes in the same commit.
Regenerate from the spec with a third implementation — not from the Swift or Kotlin code, for
the reason above — and expect both test suites to fail until both are updated.

## What is covered

| Section | Asserts |
|---|---|
| `keys` | HKDF chain from the pre-shared key to the two directional keys |
| `frames` | AES-GCM sealing at counters 0, 1 and 65535, in both directions |
| `pairing.valid` | The `cmb://pair` payload parses to the right host id, key and name |
| `pairing.invalid` | Malformed payloads are rejected rather than partly accepted |

Counter 65535 is in there because a counter that spans more than one byte of the nonce is
exactly where a hand-written big-endian conversion goes wrong.

The frame envelope itself — `{"n":…,"c":"…"}` — is deliberately not pinned byte for byte. JSON
object key order carries no meaning, the two serialisers emit different orders, and asserting on
it would test the serialisers rather than the protocol. Tests assert on the decoded counter and
ciphertext instead.
