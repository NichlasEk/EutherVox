import json, pathlib, urllib.request, concurrent.futures, hashlib, shutil
import argparse

parser = argparse.ArgumentParser()
parser.add_argument("--root", type=pathlib.Path, required=True)
root = parser.parse_args().root / "weights"
root.mkdir(parents=True, exist_ok=True)
revision = "c5fdb5ccb189668d56333f77ba2629f4cd7535f4"
api = json.load(
    urllib.request.urlopen(
        "https://huggingface.co/api/models/k2-fsa/OmniVoice/revision/"
        + revision
        + "?blobs=true"
    )
)
files = [r for r in api["siblings"] if not r["rfilename"].endswith(".gitattributes")]


def fetch(f):
    name = f["rfilename"]
    p = root / name
    p.parent.mkdir(parents=True, exist_ok=True)
    if not p.exists() or p.stat().st_size != f.get("size"):
        print("Downloading", name, flush=True)
        with (
            urllib.request.urlopen(
                f"https://huggingface.co/k2-fsa/OmniVoice/resolve/{revision}/{name}"
            ) as src,
            p.with_suffix(".part").open("wb") as out,
        ):
            shutil.copyfileobj(src, out)
        p.with_suffix(".part").rename(p)
    with p.open("rb") as data:
        digest = hashlib.file_digest(data, "sha256").hexdigest()
    expected = f.get("lfs", {}).get("sha256")
    if expected and digest != expected:
        raise ValueError("Checksum mismatch " + name)
    print("Verified", name, p.stat().st_size, flush=True)
    return {"file": name, "bytes": p.stat().st_size, "sha256": digest}


with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    records = list(pool.map(fetch, files))
(root.parent / "download-manifest.json").write_text(
    json.dumps(
        {"repository": "k2-fsa/OmniVoice", "revision": revision, "files": records},
        indent=2,
    )
)
