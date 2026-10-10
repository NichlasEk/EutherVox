#!/usr/bin/env bash
set -euo pipefail
kitten_root="${1:-/run/media/nichlas/Kingston1TB/models/KittenTTS-2}"
worker_dir="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$kitten_root"
export UV_PROJECT_ENVIRONMENT="$kitten_root/venv"
export UV_CACHE_DIR="$kitten_root/uv-cache"
uv sync --project "$worker_dir" --frozen
if [ ! -d "$kitten_root/cpp-source/.git" ]; then
  git clone https://github.com/KittenML/kitten-tts-2-cpp.git "$kitten_root/cpp-source"
fi
git -C "$kitten_root/cpp-source" checkout 1ce0bb504e5452795b52ca9a3c3950e982d82bb1
git -C "$kitten_root/cpp-source" submodule update --init vendor/kitten-text-processing
cmake -S "$kitten_root/cpp-source" -B "$kitten_root/cpp-source/build" \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_STANDARD=20 -DCMAKE_CXX_STANDARD_REQUIRED=ON \
  -DGGML_CUDA=OFF -DGGML_METAL=OFF -DLLAMA_BUILD_KITTEN_TTS=ON \
  -DLLAMA_BUILD_SERVER=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF \
  -DCMAKE_PREFIX_PATH="$("$kitten_root/venv/bin/python" -c 'import torch; print(torch.utils.cmake_prefix_path)')"
cmake --build "$kitten_root/cpp-source/build" --target kitten-tts -j 6
"$kitten_root/venv/bin/python" "$worker_dir/download_model.py" --root "$kitten_root"
