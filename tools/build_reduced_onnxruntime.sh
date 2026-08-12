#!/usr/bin/env bash
set -euo pipefail

# Build the Java-enabled, API 21 and 16 KB-compatible ONNX Runtime used by Commons ML.
# The app loads ORT-format files, so the official minimal build is applicable.
# The script regenerates the checked-in runtime JAR and native libraries after
# each ABI build so the Android library AAR is self-contained.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ORT_VERSION="${ORT_VERSION:-v1.22.0}"
ORT_PYTHON_VERSION="${ORT_PYTHON_VERSION:-1.22.0}"
ORT_DIR="${ORT_DIR:-${TMPDIR:-/tmp}/onnxruntime-${ORT_VERSION}}"
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/Users/Shared/Library/Android/sdk}}"
NDK_DIR="${ANDROID_NDK_HOME:-${SDK_DIR}/ndk/28.2.13676358}"
CMAKE_DIR="${CMAKE_DIR:-${SDK_DIR}/cmake/4.1.2}"
PY_ENV="${PY_ENV:-${TMPDIR:-/tmp}/ort-venv}"
PYTHON_BIN="${PYTHON_BIN:-python3.13}"
OPS_CONFIG="${ROOT_DIR}/tools/reduced_ops.config"
EIGEN_COMMIT="1d8b82b0740839c0de7f1242a3585e3390ff5f33"
EIGEN_DIR="${EIGEN_DIR:-${TMPDIR:-/tmp}/eigen-${EIGEN_COMMIT}}"

# The Java/AAR sub-build uses Gradle and reads the SDK from the environment,
# unlike the native CMake step which receives --android_sdk_path explicitly.
export ANDROID_HOME="${SDK_DIR}"
export ANDROID_SDK_ROOT="${SDK_DIR}"

if [[ ! -d "${ORT_DIR}" ]]; then
  git clone --recursive --branch "${ORT_VERSION}" --depth 1 \
    https://github.com/microsoft/onnxruntime.git "${ORT_DIR}"
fi

PYTHON_VERSION="$("${PYTHON_BIN}" -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")')"
case "${PYTHON_VERSION}" in
  3.13) ;;
  *)
    echo "Python ${PYTHON_VERSION} is unsupported for ONNX Runtime 1.22.0; use Python 3.10-3.13 or set PYTHON_BIN." >&2
    exit 1
    ;;
esac
if [[ -x "${PY_ENV}/bin/python" ]]; then
  ENV_PYTHON_VERSION="$("${PY_ENV}/bin/python" -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")')"
  if [[ "${ENV_PYTHON_VERSION}" != "${PYTHON_VERSION}" ]]; then
    echo "Recreating ${PY_ENV} for Python ${PYTHON_VERSION} (found ${ENV_PYTHON_VERSION})."
    rm -rf "${PY_ENV}"
  fi
fi
"${PYTHON_BIN}" -m venv "${PY_ENV}"
"${PY_ENV}/bin/python" -m pip install \
   'onnx==1.18.0' \
   'flatbuffers==25.2.10' \
   "onnxruntime==${ORT_PYTHON_VERSION}"

MODEL_DIR="${ROOT_DIR}/library/src/main/assets/models"
CONVERTED_MODEL_DIR="$(mktemp -d)"
trap 'rm -rf "${CONVERTED_MODEL_DIR}"' EXIT
mkdir -p "${MODEL_DIR}"
# Remove intermediates from older versions of this script. These filenames are
# generated exclusively by the ORT converter and are never loaded by the app.
rm -f "${MODEL_DIR}"/*.required_operators*.config "${MODEL_DIR}"/*.with_runtime_opt.ort 2>/dev/null || true
for model in "${ROOT_DIR}"/tools/source_models/*.onnx; do
  model_name="$(basename "${model}")"
  "${PY_ENV}/bin/python" -m onnxruntime.tools.convert_onnx_models_to_ort \
    "${model}" --output_dir "${CONVERTED_MODEL_DIR}"
  generated="${CONVERTED_MODEL_DIR}/${model_name%.onnx}.ort"
  test -f "${generated}" || { echo "Missing converted model: ${generated}" >&2; exit 1; }
  # The converter also emits runtime-optimized variants and operator configs.
  # Those are build intermediates, not runtime assets consumed by the library.
  cp "${generated}" "${MODEL_DIR}/"
done

"${PY_ENV}/bin/python" "${ORT_DIR}/tools/python/create_reduced_build_config.py" \
  --format ORT "${CONVERTED_MODEL_DIR}" "${OPS_CONFIG}"
sed -i.bak '/^#/d' "${OPS_CONFIG}"
rm -f "${OPS_CONFIG}.bak"

if [[ ! -d "${EIGEN_DIR}" ]]; then
  git clone --filter=blob:none --no-checkout \
    https://gitlab.com/libeigen/eigen.git "${EIGEN_DIR}"
  git -C "${EIGEN_DIR}" fetch --depth 1 origin "${EIGEN_COMMIT}"
  git -C "${EIGEN_DIR}" checkout --detach FETCH_HEAD
fi

BUILD_ARGS=( \
  --android \
  --android_sdk_path="${SDK_DIR}" \
  --android_api=21 \
  --android_ndk_path="${NDK_DIR}" \
  --cmake_path="${CMAKE_DIR}/bin/cmake" \
  --ctest_path="${CMAKE_DIR}/bin/ctest" \
  --minimal_build \
  --include_ops_by_config="${OPS_CONFIG}" \
  --build_java \
  --target onnxruntime4j_jni \
  --config Release \
  --skip_tests
)

BUILD_ARGS+=(--cmake_extra_defines "FETCHCONTENT_SOURCE_DIR_EIGEN3=${EIGEN_DIR}")
# API 21's linker requires the legacy DT_HASH table. Keep GNU hash as well so
# the same binaries remain optimized for newer Android releases.
BUILD_ARGS+=(--cmake_extra_defines \
  "CMAKE_SHARED_LINKER_FLAGS=-Wl,--hash-style=both" \
  "CMAKE_MODULE_LINKER_FLAGS=-Wl,--hash-style=both" \
  "CMAKE_EXE_LINKER_FLAGS=-Wl,--hash-style=both")

RUNTIME_JAR_DEST="${ROOT_DIR}/library/libs/onnxruntime-android-1.22.0-reduced.jar"
JNI_DEST="${ROOT_DIR}/library/src/main/jniLibs"
MERGE_DIR="$(mktemp -d)"
trap 'rm -rf "${MERGE_DIR}" "${CONVERTED_MODEL_DIR}"' EXIT
for ABI in armeabi-v7a arm64-v8a; do
  ABI_BUILD_DIR="${ORT_DIR}/build-${ABI}"
  PATH="${PY_ENV}/bin:${PATH}" "${ORT_DIR}/build.sh" \
    "${BUILD_ARGS[@]}" --android_abi="${ABI}" --build_dir="${ABI_BUILD_DIR}"
  AAR_SOURCE="${ABI_BUILD_DIR}/Release/java/build/android/outputs/aar/onnxruntime-release.aar"
  test -f "${AAR_SOURCE}" || { echo "Missing ONNX Runtime AAR for ${ABI}: ${AAR_SOURCE}" >&2; exit 1; }
  ABI_DIR="${MERGE_DIR}/${ABI}"
  mkdir -p "${ABI_DIR}"
  unzip -q "${AAR_SOURCE}" "jni/${ABI}/*.so" -d "${MERGE_DIR}/aar-${ABI}" || { echo "AAR contains no native libraries for ${ABI}" >&2; exit 1; }
  cp "${MERGE_DIR}/aar-${ABI}"/jni/"${ABI}"/*.so "${ABI_DIR}/"
  READELF="${NDK_DIR}/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-readelf"
  for library in "${ABI_DIR}"/*.so; do
    "${READELF}" -d "${library}" | grep -q '(HASH)' || { echo "${library} is missing DT_HASH" >&2; exit 1; }
    "${READELF}" -d "${library}" | grep -q '(GNU_HASH)' || { echo "${library} is missing DT_GNU_HASH" >&2; exit 1; }
  done
done
rm -rf "${MERGE_DIR}/aar-"* "${JNI_DEST}/armeabi-v7a" "${JNI_DEST}/arm64-v8a"
mkdir -p "${JNI_DEST}"
unzip -p "${AAR_SOURCE}" classes.jar > "${RUNTIME_JAR_DEST}"
for ABI in armeabi-v7a arm64-v8a; do
  mkdir -p "${JNI_DEST}/${ABI}"
  cp "${MERGE_DIR}/${ABI}"/*.so "${JNI_DEST}/${ABI}/"
done
echo "Copied reduced ONNX Runtime to: ${RUNTIME_JAR_DEST} and ${JNI_DEST}"
