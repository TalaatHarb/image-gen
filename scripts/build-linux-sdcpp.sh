#!/usr/bin/env bash
set -euo pipefail

release=master-1050-58bb3c1
commit=58bb3c1b1cd4749e94e39f537e9adfce295dcaee
variant=${1:-}
models=${2:-"$HOME/.image-gen/models"}

if [[ $(uname -s) != Linux || $(uname -m) != x86_64 || ( $variant != cpu && $variant != vulkan ) || $# -gt 2 ]]; then
    echo "Usage on Linux x86_64: bash scripts/build-linux-sdcpp.sh cpu|vulkan [model-cache-directory]" >&2
    exit 2
fi

for tool in git cmake c++; do
    if ! command -v "$tool" >/dev/null; then
        echo "Missing $tool. Install git, cmake and a C++ build toolchain; see README Linux instructions." >&2
        exit 1
    fi
done
if [[ $variant == vulkan ]] && ! command -v glslc >/dev/null; then
    echo "Missing glslc. Install the Vulkan development packages; see README Linux instructions." >&2
    exit 1
fi

work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
git init -q "$work/source"
git -C "$work/source" remote add origin https://github.com/leejet/stable-diffusion.cpp.git
git -C "$work/source" fetch --depth 1 origin "$commit"
git -C "$work/source" checkout --detach FETCH_HEAD
git -C "$work/source" submodule update --init --depth 1 ggml

vulkan=OFF
if [[ $variant == vulkan ]]; then vulkan=ON; fi
cmake -S "$work/source" -B "$work/build" -DCMAKE_BUILD_TYPE=Release \
    -DSD_VULKAN="$vulkan" -DSD_WEBP=OFF -DSD_WEBM=OFF \
    -DSD_SERVER_BUILD_FRONTEND=OFF -DSD_BUILD_SHARED_LIBS=OFF -DSD_BUILD_SHARED_GGML_LIB=OFF
cmake --build "$work/build" --config Release --target sd-cli --parallel "${CMAKE_BUILD_PARALLEL_LEVEL:-2}"
"$work/build/bin/sd-cli" --help >/dev/null

destination="$models/sd-cpp-$release-$variant-local"
mkdir -p "$destination"
install -m 755 "$work/build/bin/sd-cli" "$destination/sd-cli.new"
mv -f "$destination/sd-cli.new" "$destination/sd-cli"
echo "Installed $variant sd-cli at $destination/sd-cli"
