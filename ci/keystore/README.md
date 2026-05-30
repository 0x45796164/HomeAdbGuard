# CI keystore

`ci.jks` here is a **dummy keystore committed in the clear** so that the CI
workflow can produce a signed release APK without per-build key rotation.

Passwords — also intentionally committed:

```
storeFile=ci/keystore/ci.jks
storePassword=android
keyAlias=ci
keyPassword=android
```

This is *not* a release identity. Anyone can re-sign an APK with this key.
The reason it exists is to give the rolling [latest release](https://github.com/0x45796164/HomeAdbGuard/releases/latest)
a stable signature, so successive APKs install over each other without an
uninstall step.

For a stronger signature, build from source with your own keystore. See
[docs/DEVELOPMENT.md → Release signing](../../docs/DEVELOPMENT.md#release-signing).
