#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
model_root="${OMNIVOICE_ROOT:-/run/media/nichlas/Kingston1TB/models/OmniVoice}"
experiment="${1:?Usage: voice-lab/run.sh /absolute/path/to/new-experiment}"
if [[ "$experiment" != /* ]]; then
  echo 'Use an absolute experiment directory.' >&2
  exit 2
fi
export HF_HOME="$model_root/hf-cache"
export HF_HUB_OFFLINE=1
export TOKENIZERS_PARALLELISM=false
# Independent processes release all OmniVoice GPU memory before CPU cloning.
timeout --signal=TERM --kill-after=10s 600s "$model_root/venv/bin/python" \
  "$repo_dir/voice-lab/design.py" --model-root "$model_root" --output "$experiment"
timeout --signal=TERM --kill-after=10s 600s "$repo_dir/moss-worker/.venv/bin/python" \
  "$repo_dir/voice-lab/clone_moss.py" --experiment "$experiment" \
  --moss-model "$repo_dir/models/moss-tts-nano-onnx"
"$model_root/venv/bin/python" "$repo_dir/voice-lab/compare.py" "$experiment"
