# Commons ML

Commons ML is a standard Android library for on-device face and license-plate
detection, plus a small Android demo for reviewing detections. It is Android-only
by design; the repository does not use Kotlin Multiplatform.

## Modules

- `library` - reusable Android library with the public detection contract and
  bundled ONNX Runtime YuNet backends.
- `demo` - standalone Android application that exercises the library and shows
  optional ajpegtran-based JPEG export.
- `tools` - reproducible model conversion and native-runtime maintenance scripts.

The stale `app`, `benchmark`, and `commons-ai` modules are intentionally not part
of the repository.

## Using the library

Create one `CommonsVision` instance for the lifetime of the screen or owner and
close it when that owner is destroyed:

```kotlin
val vision = CommonsVision(context)
try {
    val result = vision.detect(bitmap, DetectionOptions())
    when (result) {
        is DetectionResult.Success -> consume(result.detections)
        is DetectionResult.Partial -> showUnsupported(result.skipped)
        is DetectionResult.Unavailable -> showError(result.reason)
    }
} finally {
    vision.close()
}
```

`detect` is suspendable and should run from a coroutine on a worker dispatcher.
Detection bounds are pixel coordinates in the exact bitmap supplied. The library
does not depend on ajpegtran and does not blur or pixelate images.

Face and plate detection use the bundled reduced ONNX Runtime on all supported
Android API levels, including API 21. Runtime failures are represented by typed
`MlRuntimeException` subclasses and are logged with a stable error code.

## Architecture

The library keeps the detection domain separate from the Android inference
runtime:

- `common` contains the public detection contract and validation types.
- `vision` contains model metadata, image preprocessing, output decoding,
  crop planning, geometry, and non-maximum suppression.
- `runtime` contains the internal `ModelRuntime`/`ModelSession` boundary and
  the ONNX Runtime implementation. Vision code never imports ONNX Runtime
  classes, so another Android engine can replace this backend without changing
  the detection contract.

Runtime sessions are tracked by `OrtRuntime`, closed idempotently, and released
even when one detector fails during cleanup. Calling detection after closing the
facade raises `RuntimeClosedException`.

## Build and publish

Use JDK 17, Android SDK 36, NDK 28.2.13676358, and CMake 4.1.2:

```bash
export JAVA_HOME=/path/to/jdk-17
./gradlew :library:test
./gradlew :library:assembleRelease
./gradlew :demo:assembleDebug
./gradlew :library:publishToMavenLocal -PsignAllPublications=false
```

The demo uses `arm64-v8a` native libraries from the library dependency. To verify
16 KB native alignment after building an artifact:

```bash
tools/verify_native_alignment.sh library/build/outputs/aar/library-release.aar
```

The library uses Maven coordinates
`io.github.commons-app:commons-ml:0.1.0`. Maven Central publishing and signing
remain configured through the Vanniktech plugin; provide credentials in CI before
releasing. This cleanup does not publish artifacts automatically.

## Model provenance

The bundled models are converted ORT files derived from the OpenCV Zoo sources.
The original ONNX files remain under `tools/source_models/` for auditability and
reproducible conversion. See
[`library/src/main/assets/models/README.md`](library/src/main/assets/models/README.md)
for source URLs, licenses, checksums, and the active model inventory.

To regenerate ORT assets and the reduced runtime, install Python 3, Git, the
Android SDK/NDK, and CMake, then run:

```bash
tools/build_reduced_onnxruntime.sh
```

The script is maintenance tooling, not part of a normal application build. It
passes `--hash-style=both` to the native linker so the bundled libraries retain
the legacy `DT_HASH` table required by API 21 while also keeping `DT_GNU_HASH`
for newer Android releases. The native alignment verifier checks this
compatibility in addition to 16 KB `LOAD` segment alignment.

## Contributing

Read [`CONTRIBUTING.md`](CONTRIBUTING.md) before opening a pull request.
Security reports should follow [`SECURITY.md`](SECURITY.md). CI runs JDK 17
library tests and Android debug/release compilation for every push and pull
request.

## License

The library and demo source are MIT-licensed. Bundled models and reused decoder
logic retain their upstream licenses; consult the model provenance document
before redistribution.
