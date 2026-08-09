# Contributing to Pikto

Thanks for looking. Issues and pull requests are both welcome.

## What is most useful

**Real-device edge cases.** Pikto talks to two platform APIs that behave differently across OS
versions, OEM skins and library sizes. A bug report that names the device, OS version and library
size is worth more than almost any feature.

**Filling the gaps.** The "What Pikto does not do" list in the README is a roadmap as much as a
disclaimer. Change observation and write support are the two most commonly wanted. Open an issue
before starting either, so we can agree the API shape first.

**Documentation.** If a KDoc comment did not answer the question you had, that is a bug.

## Building

```bash
git clone https://github.com/daniatitienei/pikto.git
cd pikto
./gradlew build
```

You need JDK 17+, the Android SDK, and Xcode for the iOS targets. Point Gradle at your SDK with a
`local.properties`:

```properties
sdk.dir=/Users/you/Library/Android/sdk
```

`./gradlew build` compiles every target and runs the common tests on both the JVM host and the iOS
simulator. `./gradlew dokkaGenerate` builds the API docs into `build/dokka/`.

## House style

**No em dashes.** Anywhere. Not in code comments, not in docs, not in commit messages.

**Comments explain why, never what.** The code already says what it does. A comment earns its
place by recording the thing that is not visible: the platform behaviour that forced this shape,
the bug the obvious version caused, the measurement behind a constant. If a comment restates the
line under it, delete the comment.

**Public API is documented.** Every public declaration gets KDoc, and every module has
`explicitApi()` on so the compiler enforces visibility being deliberate.

**Names are the documentation you cannot skip.** Prefer a longer name over a shorter name plus a
comment.

**Platform code stays in platform source sets.** `commonMain` never learns what a `Context` or a
`PHAsset` is.

## Testing

Logic that can be tested without a device belongs in `commonTest`, and there should be a test for
it. Batching, cache eviction, update folding, decode deduplication: all of that is device-free and
all of it is where the subtle bugs live.

Anything that genuinely needs MediaStore or PhotoKit is tested by hand on a device. Say in the pull
request what you ran it on.

## Pull requests

- One concern per pull request.
- `./gradlew build` passes.
- New public API comes with KDoc and a README mention if it is something users would look for.
- Note any behaviour change in `CHANGELOG.md` under "Unreleased".

## Releasing

Maintainers only. See [RELEASING.md](RELEASING.md) for the Sonatype account setup and the
per-release steps.
