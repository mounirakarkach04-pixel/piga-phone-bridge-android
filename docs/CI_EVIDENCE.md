# Current CI Evidence

The pre-0.2.0 baseline commit `237828b2172224a3bf2fcf647ca8f530a15d6961` completed the Android debug workflow successfully and produced a GitHub Actions artifact containing the APK and its SHA-256 file.

Verified baseline debug APK SHA-256:

```text
469ab6741f98d7c2819be5f4808126534ca4e4505b3a83d0b52bb70608d33335
```

This baseline evidence does not substitute for the new `0.2.0` branch CI. The release candidate must generate fresh test, lint, build and hash evidence from the final source commit.
