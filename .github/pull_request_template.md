<!-- Thanks for the PR. Please skim CONTRIBUTING.md and tick the boxes below. -->

## Summary

<!-- One or two sentences on what changes and why. -->

## Checklist

- [ ] No new third-party Android dependencies added.
- [ ] App still does not request `INTERNET`. No cleartext traffic added.
- [ ] Fail-closed posture preserved (untrusted/unknown network → both
      `adb_wifi_enabled` and `development_settings_enabled` requested off).
- [ ] If this PR adds, removes, or re-routes any call to `SecureSettings`
      (or otherwise changes when `WRITE_SECURE_SETTINGS`-protected globals
      are written), it is explicitly called out in the summary above.
- [ ] Ran `./gradlew assembleDebug testDebug lintDebug` locally and all
      three tasks pass.
- [ ] User-visible changes are reflected in `docs/USAGE.md`.

## Testing

<!-- How did you verify this? Device + Android version, ADB checks
     (settings get global ...), manual scenarios exercised. -->
