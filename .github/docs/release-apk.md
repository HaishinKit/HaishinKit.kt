# 📦 Sample APK releases

Pushing a `*.*.*` tag runs the Release workflow. After review checks pass, it
builds the sample's signed release variant and uploads `HaishinKit-<tag>.apk` to
the matching GitHub Release, creating the release if necessary. Existing release
notes are preserved; reruns replace the same APK asset. Documentation publishing
runs afterward. Release builds exclude the debug MCP server and LeakCanary.

## 🔑 Signing setup

Before the first release, create a dedicated signing keystore (or reuse the
existing sample distribution key). Keep a secure backup and use the same key
for every release so installed samples can be updated.

```sh
keytool -genkeypair -keystore sample-release.jks -alias haishinkit-sample \
  -keyalg RSA -keysize 3072 -validity 10000
```

Register these repository Actions secrets:

| Secret | Value |
|---|---|
| `APK_KEYSTORE_BASE64` | Base64-encoded keystore, on one line |
| `APK_KEYSTORE_PASSWORD` | Keystore password |
| `APK_KEY_ALIAS` | Signing key alias, e.g. `haishinkit-sample` |
| `APK_KEY_PASSWORD` | Signing key password |

For example, encode the keystore using Ruby's standard library:

```sh
ruby -rbase64 -e 'puts Base64.strict_encode64(File.binread(ARGV.fetch(0)))' sample-release.jks
```

Do not commit the keystore or passwords. The workflow restores the keystore into
the runner's temporary directory and deletes it when the build step exits.
Missing signing secrets fail the build rather than distributing an unsigned APK.
The version name is the tag; the version code is the Release workflow run number.
Keep that counter increasing if replacing the workflow. Reruns keep the same code.

## 📱 Install on Android

On Android 10 or later, open the repository's **Releases** page, download the APK
under **Assets**, and open it. If prompted, allow **Install unknown apps** for
the browser or file manager, then confirm installation. Device policies and
Android security checks can require additional steps or prevent installation.
A debug sample installed with a different signing key must be uninstalled first;
uninstalling removes its app data.
