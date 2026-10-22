# Contributing

Start with [README.md](README.md), then read [AGENTS.md](AGENTS.md) for repository boundaries.

Use JDK 17 and Android SDK 35. Keep changes focused and explain the user-visible result.

```sh
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/export_public_source.py --check
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileReleaseKotlin :app:compileDebugAndroidTestKotlin :app:lintDebug
```

UI changes should include screenshots using fictional fixtures, including dark theme and larger text where applicable. Instrumentation compilation is not a device test. State the device, selected tests and date when reporting device results.

Never add service configuration, tokens, personal health records, raw AI prompts/responses or local databases. AI probes require explicit opt-in and are excluded from ordinary CI. Use fake gateways in tests. Preserve explicit migrations, user isolation, cancellation and draft confirmation. Do not introduce destructive migrations or change historical schemas to make tests pass.

The GitHub workflow runs local tests, builds and lint; it does not contact a model or certify live AI, Play Integrity or clinical validity. Its pinned actions follow the upstream [checkout](https://github.com/actions/checkout), [setup-java](https://github.com/actions/setup-java) and [Gradle actions](https://github.com/gradle/actions) documentation.

No project-wide open-source license has been selected yet. Contribution and redistribution terms must be settled before accepting outside contributions.
