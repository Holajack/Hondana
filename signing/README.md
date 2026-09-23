# Signing key

`hondana-release.jks` signs every release build (alias `hondana`; the
passwords are in `signing.properties`). Android only installs an update over an
existing app when both are signed with the same key, so this file is what lets
each new APK replace the last one without losing your library.

It is committed because this repository is **private**. Anyone who can read the
repo can sign APKs that your phone would accept as updates to Hondana.

**Before making the repository public**, move the key into Actions secrets and
delete the files here:

1. Add these repository secrets (Settings → Secrets and variables → Actions):
   - `HONDANA_KEYSTORE_BASE64`: output of `base64 -w0 signing/hondana-release.jks`
   - `HONDANA_KEYSTORE_PASSWORD`, `HONDANA_KEY_PASSWORD`: from `signing.properties`
   - `HONDANA_KEY_ALIAS`: `hondana`
2. `git rm signing/hondana-release.jks signing/signing.properties`. The key
   stays in git history, so treat it as exposed if the old history is ever
   published. Rewrite history, or generate a new key and reinstall once.

The workflow prefers the secrets whenever `HONDANA_KEYSTORE_BASE64` is set.

Keep a copy of the keystore somewhere safe outside GitHub too. If it is lost,
the next build can't update the installed app: back up your library (More →
Backup and restore), uninstall, install the new build, and restore.
