# Release signing

Android installs an update only when it is signed with the same key as the app
already on the phone. Official Hondana builds are all signed with one release
key, so every build installs over the last and keeps the library.

## Where the key lives

- `hondana-release.tar.gpg` holds the keystore (`hondana-release.jks`, alias
  `hondana`) and its passwords (`signing.properties`), encrypted with AES-256.
  It is safe to keep in a public repo as long as the passphrase stays secret.
- The passphrase is the repository secret **`SIGNING_PASSPHRASE`** (Settings →
  Secrets and variables → Actions). It exists nowhere else.
- CI decrypts the bundle into this folder before building
  (`.github/workflows/build.yml`, step "Unlock the signing key"). The decrypted
  files are git-ignored and must never be committed.
- The build refuses to publish an APK signed with anything else.

The first build that finds no bundle here creates a new key, encrypts it and
commits it (`Signing: new release key, stored encrypted`). That happened once,
in September 2026.

## The old key is retired

Until September 2026 the keystore and its password were committed in plain text
while the repo was private. They were exposed when the repo went public, so
that key was replaced. Don't trust any APK signed with the old certificate
(SHA-256 `C7:5B:CA:…:71:F9`). Installs made with it had to be uninstalled once.

## Building a signed APK yourself

```bash
gpg --decrypt signing/hondana-release.tar.gpg | tar -x -C signing   # asks for the passphrase
./gradlew assembleRelease
rm signing/hondana-release.jks signing/signing.properties
```

Without the key, `assembleRelease` signs with the debug key: fine for testing,
but it won't install over an official build.

## If the passphrase is lost or leaks

The key can't be recovered without the passphrase. Delete
`hondana-release.tar.gpg`, set a new `SIGNING_PASSPHRASE`, and push: CI creates
a new key. Every phone then needs one uninstall and reinstall (back up first).
