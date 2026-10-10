"""Build an offline listening comparison; never autoplay or upload audio."""

import argparse
import html
import json
from pathlib import Path
from urllib.parse import quote


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("experiment", type=Path)
    args = parser.parse_args()
    design = json.loads((args.experiment / "design.json").read_text())
    moss = json.loads((args.experiment / "moss.json").read_text())
    cloned = {row["name"]: row for row in moss["candidates"]}
    asr_path = args.experiment / "asr-check.json"
    transcripts = {}
    if asr_path.exists():
        transcripts = {
            row["file"]: row["transcript"]
            for row in json.loads(asr_path.read_text())["samples"]
        }

    def player(filename):
        if Path(filename).name != filename:
            raise ValueError("Invalid audio filename")
        result = f'<audio controls preload="none" src="{quote(filename)}"></audio>'
        if filename in transcripts:
            result += (
                '<p class="muted">Automatisk transkription (kan innehålla fel): '
                + html.escape(transcripts[filename])
                + "</p>"
            )
        return result

    cards = []
    for row in design["candidates"]:
        clone = cloned[row["name"]]
        cards.append(
            f"""<article><h2>{html.escape(row['name'])}</h2>
<p class="muted">{html.escape(row['instructions'])}</p>
<h3>1. Designad svensk referens · OmniVoice</h3>{player(row['reference'])}
<p>{row['design_seconds']:.1f} s generering · {row['reference_seconds']:.1f} s ljud</p>
<h3>2. Ny mening · OmniVoice-kloning</h3>{player(row['omnivoice_clone'])}
<p>{row['clone_seconds']:.1f} s generering · {row['clone_audio_seconds']:.1f} s ljud</p>
<h3>3. Samma nya mening · MOSS-Nano på CPU</h3>{player(clone['file'])}
<p>{clone['first_audio_seconds']:.1f} s till första ljud · {clone['wall_seconds']:.1f} s totalt · {clone['audio_seconds']:.1f} s ljud</p></article>"""
        )
    page = """<!doctype html><html lang="sv"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Scryers röstlabb</title><style>
body{margin:0;background:#101b23;color:#e8f3ef;font:17px/1.55 system-ui,sans-serif}main{max-width:1100px;margin:auto;padding:32px 22px}h1{font-size:2.1rem}h2{color:#87e3d4}h3{font-size:1rem;margin-top:24px}.muted{color:#bbcad2}section{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,360px),1fr));gap:22px}article{background:#1b2b35;border:1px solid #39565e;border-radius:18px;padding:22px}audio{width:100%}aside{border-left:3px solid #87e3d4;padding:0 18px;margin:28px 0}a{color:#87e3d4}
</style><main><p class="muted">EutherVox · lokalt lyssningsprov</p><h1>En egen röst åt Siaren</h1>
<p>Lyssna efter svensk betoning, naturlighet och om rösten behåller sin karaktär mellan motorerna. Allt ljud är syntetiskt. Ingen av rösterna har aktiverats som standard i Vox.</p>"""
    page += f'<aside><b>Referenstext</b><p>{html.escape(design["reference_text"])}</p><b>Gemensam testmening</b><p>{html.escape(design["test_text"])}</p></aside>'
    page += (
        "<section>"
        + "".join(cards)
        + '</section><p class="muted">Tiderna är mätta på denna dator. Automatisk ljudkontroll ersätter inte din bedömning av uttal och röstkaraktär.</p></main></html>'
    )
    (args.experiment / "index.html").write_text(page)


if __name__ == "__main__":
    main()
