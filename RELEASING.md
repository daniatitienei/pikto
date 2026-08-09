# Releasing Pikto to Maven Central

The Gradle side is already configured. What is left is a one-time account setup, then a tag per
release.

## One time: the Sonatype account

Maven Central is operated by Sonatype. Since 2024 the way in is the **Central Portal** at
[central.sonatype.com](https://central.sonatype.com). The older OSSRH / `oss.sonatype.org` flow
that most blog posts describe is retired, so ignore anything mentioning `s01.oss.sonatype.org`.

1. **Sign in** at central.sonatype.com. Signing in with GitHub is the least work, because it makes
   the next step almost automatic.

2. **Claim the namespace.** Go to *Namespaces* and add `io.github.daniatitienei`.

   A namespace is the Maven `groupId`, and you have to prove you control it. `io.github.<user>` is
   the free option precisely because GitHub account ownership is the proof. The Portal shows a
   verification code that looks like `abc123xyz456`. Create a **public** repository under your
   GitHub account named exactly that code, click *Verify*, and delete the repository afterwards.

   Verification is usually instant. The namespace stays verified forever, so this never has to
   happen again.

3. **Generate a user token.** *Account* → *Generate User Token*. You get a username and password
   pair that look like random strings. These are what the build authenticates with. They are not
   your login details, and you can revoke them without touching the account.

## One time: the signing key

Maven Central rejects anything unsigned. It wants a PGP signature next to every artifact so
consumers can verify nothing was tampered with in transit.

Generate a key:

```bash
gpg --full-generate-key
```

Take the defaults (RSA, 4096 bits), give it your name and the email on your Sonatype account, and
set a passphrase. Then find its id:

```bash
gpg --list-secret-keys --keyid-format=long
# sec   rsa4096/A1B2C3D4E5F6A7B8 2026-08-09 [SC]
#                 ^^^^^^^^^^^^^^^^ this is the key id
```

Publish the **public** half to a keyserver, so Central can check the signatures against it:

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys A1B2C3D4E5F6A7B8
```

Export the **private** half for CI:

```bash
gpg --armor --export-secret-keys A1B2C3D4E5F6A7B8
```

That prints a `-----BEGIN PGP PRIVATE KEY BLOCK-----` block. Copy the whole thing, headers
included. Guard it like a password: anyone holding it can sign releases as you.

## One time: the GitHub secrets

In the repository, *Settings* → *Secrets and variables* → *Actions*, add four:

| Secret | Value |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | The token username from the Portal |
| `MAVEN_CENTRAL_PASSWORD` | The token password from the Portal |
| `SIGNING_KEY` | The whole armored private key block |
| `SIGNING_KEY_PASSWORD` | The passphrase you set on the key |

`.github/workflows/publish.yml` maps these onto the Gradle properties the publishing plugin reads,
so nothing else needs configuring.

## Every release

1. Set `VERSION_NAME` in `gradle.properties` to the version you are releasing, with no `-SNAPSHOT`
   suffix.
2. Move everything under *Unreleased* in `CHANGELOG.md` beneath a heading for that version.
3. Update the version in the README install snippet.
4. Commit, then tag and push:

   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```

The tag is what triggers `publish.yml`. Nothing else does, so pushing to `main` is always safe.

5. **Release the deployment.** The workflow uploads to a staging area and stops there, because the
   build sets `automaticRelease = false`. Go to *Deployments* in the Portal, check the validation
   passed, and click *Publish*. This is the last point at which a mistake is free, so it is worth
   the extra click.

   To skip it in future, change `publishToMavenCentral` to `publishAndReleaseToMavenCentral` in
   the workflow.

6. Bump `VERSION_NAME` to the next `-SNAPSHOT` and commit.

Artifacts appear on `central.sonatype.com` within a few minutes and on `search.maven.org` within a
few hours. **A published version can never be changed or deleted.** If something is wrong, the only
remedy is releasing a new version.

## Trying it without publishing anything

```bash
./gradlew publishToMavenLocal
```

Writes every artifact to `~/.m2/repository/io/github/daniatitienei/`. Add `mavenLocal()` to another
project's repositories and depend on the version as normal. This is the fastest way to check the
library from a real app before committing to a release, and it needs no account and no signing key.

## Which tasks do what

| Task | What it does |
| --- | --- |
| `publishToMavenLocal` | Local only. No account or key needed. |
| `publishToMavenCentral` | Uploads and waits for you to click Publish. |
| `publishAndReleaseToMavenCentral` | Uploads and releases in one step. |
