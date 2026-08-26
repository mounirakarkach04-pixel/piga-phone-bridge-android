# Build Reproducibility

Pinned build inputs for Android `0.2.0`:

- JDK 17 (Temurin in CI);
- Gradle 8.9 via `gradle/actions/setup-gradle`;
- Android Gradle Plugin 8.7.3;
- Kotlin Android plugin 2.0.21;
- compile/target SDK 35;
- WorkManager 2.11.2;
- JUnit 4.13.2;
- `org.json` test dependency 20240303.

The repository does not include a Gradle wrapper. CI supplies the pinned Gradle version and records the source SHA in the release manifest. Public release evidence should retain the CI run URL, artifact digest and `SHA256SUMS.txt`.
