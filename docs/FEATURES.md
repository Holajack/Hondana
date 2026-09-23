# Hondana features

What Hondana adds on top of Komikku. Each section says where the feature lives
in the app and in the code.

## Rebrand and install

- Package `com.holajack.hondana`, so it installs next to Mihon or Komikku.
- Name "Hondana" with its own icon: 本 ("book") in a speech bubble.
- Stable release signing (see `signing/README.md`), so updates install over
  each other.
- A GitHub Actions build that publishes the APK to a stable download link.
