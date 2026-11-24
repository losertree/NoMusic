"""Rebuild the bundled offline Jyutping lexicon from pinned, attributed sources."""
import gzip
import hashlib
from pathlib import Path
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
RIME = "259f0e48bba840c3a2e0d117539e96937f3d89bc"
OPENCC = "fae2a0a911c30764e669a7d8b0591cd8e852934a"


def download(repo, revision, path):
    url = f"https://raw.githubusercontent.com/{repo}/{revision}/{path}"
    with urlopen(url, timeout=60) as response:
        return response.read()


def main():
    assets = ROOT / "app/src/main/assets"
    licenses = assets / "licenses"
    licenses.mkdir(parents=True, exist_ok=True)
    (licenses / "cantonese-cc-by-4.0.txt").write_bytes(
        download("rime/rime-cantonese", RIME, "LICENSE-CC-BY"))
    (licenses / "opencc-apache-2.0.txt").write_bytes(
        download("BYVoid/OpenCC", OPENCC, "LICENSE"))
    variants = {}
    for line in download("BYVoid/OpenCC", OPENCC, "data/dictionary/TSCharacters.txt").decode("utf-8").splitlines():
        if "\t" in line and not line.startswith("#"):
            key, value = line.split("\t", 1)
            variants[key] = value.split()[0]
    readings = {}
    weights = {}
    for filename in ("jyut6ping3.chars.dict.yaml", "jyut6ping3.words.dict.yaml"):
        content = download("rime/rime-cantonese", RIME, filename).decode("utf-8")
        content = content.split("...", 1)[1]
        for line in content.splitlines():
            columns = line.split("\t")
            if len(columns) < 2:
                continue
            word, reading = columns[:2]
            # A syllable for every Han character makes highlighting deterministic.
            if not word or len(word) > 8 or len(word) != len(reading.split()):
                continue
            try:
                weight = float(columns[2].rstrip("%")) if len(columns) > 2 else 0
            except ValueError:
                weight = 0
            if word not in readings or weight > weights[word]:
                readings[word], weights[word] = reading, weight
    for word, reading in list(readings.items()):
        simplified = "".join(variants.get(char, char) for char in word)
        readings.setdefault(simplified, reading)
    # Java String.compareTo compares UTF-16 units, including supplementary Han.
    payload = "".join(f"{word}\t{readings[word]}\n" for word in sorted(readings, key=lambda value: value.encode("utf-16-be"))).encode("utf-8")
    # AAPT transparently inflates assets ending in .gz and strips the suffix.
    target = assets / "pronunciation/jyutping.lex"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(gzip.compress(payload, compresslevel=9, mtime=0))
    print(f"{len(readings)} entries, {target.stat().st_size} bytes, SHA256 {hashlib.sha256(target.read_bytes()).hexdigest()}")
    for filename in ("LICENSE.md", "kuromoji-ipadic/NOTICE.md"):
        suffix = "apache-2.0" if filename == "LICENSE.md" else "ipadic-notice"
        (licenses / f"kuromoji-{suffix}.txt").write_bytes(
            download("atilika/kuromoji", "0.9.0", filename))


if __name__ == "__main__":
    main()
