# APK Signing

The original 2.14 APK uses certificate SHA-256:
`8b04fd297ca47ab6b1618667c8aaec7232e99431e214517751f61c67cec9cdd8`.

Its private keystore was previously stored in a temporary build directory
and was not found during the 2.15 build. An APK contains the public certificate,
not the private key.

The available debug key is `C:\Users\damvi\.android\debug.keystore`.
Its certificate SHA-256 is:
`364b713d0baf12a9c45ed74b5c224b8bc8a10d24582eb61939c380456914c613`.

A build signed with that key cannot update the original installation.
The 2.14 APK is retained unchanged. Versions 2.15 through 2.19 use the
same available key and can update in place. Version 2.19 is a non-debuggable
release build, deliberately signed with the existing key to preserve the
installed app's update chain. The current artifact is
`NgheKinhThanh1925-2.19.apk`, distributed through GitHub Releases.

Do not uninstall the existing app before preserving its offline audio.
Restoring the original signing keystore allows an ordinary in-place update.
For future builds, keep the selected keystore in a durable backed-up location.

Never commit the keystore, private key, passwords or GitHub credentials.
The updater compares the downloaded package's signing identity to the
installed app, and Android verifies the signature during installation.
Switching to a new signing key without a supported signing lineage prevents
ordinary updates. Renaming an APK does not change its signing identity.

Build with `assembleRelease -PdebugKeystore=<private-keystore-path>`.
The historical property name selects the existing signing key for both
build types; it does not make the release APK debuggable.
