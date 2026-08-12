# Contributing to Commons ML

Thanks for helping improve Commons ML. Keep changes focused on the Android
library and demo, and explain behavior changes in the pull request.

## Before opening a pull request

1. Use JDK 17 and configure Android SDK 36.
2. Run `./gradlew :library:test :library:assembleRelease :demo:assembleDebug`.
3. Run `git diff --check`.
4. Update model provenance documentation when adding or replacing an asset.

Do not commit `local.properties`, downloaded images, generated APKs/AARs, or
credentials. Keep ONNX Runtime changes reproducible through the scripts under
`tools/` and document any required toolchain update.

## Design expectations

The public detection contract should not depend on ONNX Runtime classes.
Runtime-specific code belongs under `org.commons.ml.runtime`; model decoding and
geometry belong under the vision/domain layer. New resources must have explicit
ownership and idempotent cleanup.
