#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
model_root="${OMNIVOICE_ROOT:-/run/media/nichlas/Kingston1TB/models/OmniVoice}"
mkdir -p "$model_root"
UV_PROJECT_ENVIRONMENT="$model_root/venv" uv sync --project "$repo_dir/voice-lab" --frozen
"$model_root/venv/bin/python" "$repo_dir/voice-lab/download_model.py" --root "$model_root"
