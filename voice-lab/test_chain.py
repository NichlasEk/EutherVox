import importlib.util
from pathlib import Path
import numpy as np
import pytest

spec = importlib.util.spec_from_file_location(
    "voice_design", Path(__file__).with_name("design.py")
)
design = importlib.util.module_from_spec(spec)
spec.loader.exec_module(design)


@pytest.mark.parametrize(
    "audio",
    [
        np.zeros(24000 * 3),
        np.ones(20),
        np.ones(24000 * 21),
        np.ones((24000 * 3, 2)),
        np.full(24000 * 3, float("nan")),
    ],
)
def test_unusable_references_rejected(audio):
    with pytest.raises(ValueError):
        design.validate_audio(audio, 24000)


def test_valid_reference_and_distinct_reproducible_designs():
    audio = np.sin(np.arange(24000 * 8) * 0.04) * 0.2
    assert len(design.validate_audio(audio, 24000)) == 24000 * 8
    assert len({d[0] for d in design.DESIGNS}) == len(design.DESIGNS)
    assert len({d[2] for d in design.DESIGNS}) == len(design.DESIGNS)
