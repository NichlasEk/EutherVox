import urllib.request, json, pathlib, shutil, hashlib
import argparse

parser = argparse.ArgumentParser()
parser.add_argument("--root", type=pathlib.Path, required=True)
root = parser.parse_args().root
root.mkdir(parents=True, exist_ok=True)
revision = "f2eee74f4467855ad58fe16f1fb7b7df473263fd"
config = json.load(
    urllib.request.urlopen(
        f"https://huggingface.co/KittenML/kitten-tts-2/resolve/{revision}/config.json"
    )
)
assets = root / "assets"
assets.mkdir(exist_ok=True)
(assets / "config.json").write_text(json.dumps(config, indent=2))
(root / "model-revision.txt").write_text(revision + "\n")
files = [
    config["cpp"]["gguf"],
    config["cpp"]["decoders"]["default"]["torchscript"],
    config["cpp"]["decoders"]["default"]["voices"],
]
expected_hashes = {
    "cpp/model-tq2_1.gguf": "84f0a8b9986fbc01fbe6d4777703304d1cecc7b99e8c6fd2c607e0661e804d4c",
    "cpp/default/decoder.pt": "c67c1a700358695c780c64417328b74f4d6a71f7363df7cdbbcda6133d6096ae",
    "cpp/default/voices.json": "a2fbe5106d939f59b964e7780951dc402f89aceddd113b82e0539a5a2928c1ff",
}
records = []
for item in files:
    p = assets / item["file"]
    p.parent.mkdir(parents=True, exist_ok=True)
    if not p.exists() or p.stat().st_size != item["size"]:
        print("Downloading", item["file"], flush=True)
        with (
            urllib.request.urlopen(
                f'https://huggingface.co/KittenML/kitten-tts-2/resolve/{revision}/{item["file"]}'
            ) as response,
            p.with_suffix(".part").open("wb") as out,
        ):
            shutil.copyfileobj(response, out)
        p.with_suffix(".part").rename(p)
    assert p.stat().st_size == item["size"]
    records.append(
        {
            "file": item["file"],
            "bytes": p.stat().st_size,
            "sha256": hashlib.file_digest(p.open("rb"), "sha256").hexdigest(),
        }
    )
    if records[-1]["sha256"] != expected_hashes[item["file"]]:
        raise ValueError("Downloaded asset checksum mismatch")
    print("Verified", item["file"], flush=True)
(root / "download-manifest.json").write_text(
    json.dumps(
        {"repository": "KittenML/kitten-tts-2", "revision": revision, "files": records},
        indent=2,
    )
)

for remote, local in (("README.md", "MODEL_CARD.md"), ("LICENSE.md", "LICENSE.md")):
    with urllib.request.urlopen(
        f"https://huggingface.co/KittenML/kitten-tts-2/resolve/{revision}/{remote}"
    ) as response:
        (root / local).write_bytes(response.read(1000000))
