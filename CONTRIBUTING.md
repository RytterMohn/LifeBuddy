# Contributing / 参与开发

欢迎中文或英文反馈和 PR。开始前请阅读 [开发说明](docs/development.md) 和 [架构](docs/architecture.md)。

Welcome contributions in Chinese or English. Read the development and architecture guides before changing the execution loop.

## Local checks

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :fixture:assembleDebug :cli:installDist
```

Use `-PbuildJdk=21` if Java 21 is your installed toolchain. Normal tests do not call a model service. Real-provider tests are opt-in and use your own configuration; see [testing](docs/testing.md).

## Changes

- Keep changes focused on an observable behavior. Explain the problem, result and validation in the PR.
- Update both Chinese and English UI text when adding a label. Preserve user messages, app names and quoted evidence in their original language.
- Preserve existing conversations and settings. Add regression coverage for changes to persistent formats or execution recovery.
- Tool execution must retain the before-dispatch checkpoint. A timeout or lost response must not automatically repeat a possibly completed external operation.
- Keep the distinction between synthetic screens, offline test apps and real-device verification explicit.
- New tools and skills should load on demand. Avoid placing entire app or plugin catalogs in every model request.
- Do not include keys, signing files, model weights, private task traces or device screenshots containing personal data.

## Bugs

Include the LifeBuddy version, Android/device version, relevant app version, an anonymized request, expected result and actual result. A small reproducible case is more useful than an entire conversation export.

For credential exposure or unintended execution vulnerabilities, use the private process in [SECURITY.md](SECURITY.md).

Contributions are provided under the project's [MIT license](LICENSE). Third-party code must retain its applicable license and attribution.
