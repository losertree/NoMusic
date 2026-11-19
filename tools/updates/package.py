"""Stage both nondebuggable APKs and the manifest. Run from the project root."""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path


PACKAGES = {"standard": "com.nomusic.player", "cs11": "com.netease.cloudmusic"}
ORIGIN = "https://nomusic.losertree.de"


def signer(tool, apk):
    result = subprocess.run([str(tool), "verify", "--min-sdk-version", "16", "--print-certs", str(apk)],
                            check=True, capture_output=True, text=True, shell=True if tool.suffix == ".bat" else False)
    certs = re.findall(r"certificate SHA-256 digest: ([a-f0-9]+)", result.stdout)
    if len(certs) != 1:
        raise SystemExit(f"Expected a single signing certificate: {apk}")
    return certs[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apksigner", type=Path, required=True)
    parser.add_argument("--out", type=Path, default=Path("build/update-site"))
    parser.add_argument("--notes", type=Path, default=Path("tools/updates/release-notes.txt"))
    args = parser.parse_args()
    manifest = {"schemaVersion": 1, "packages": {}}
    notes = args.notes.read_text(encoding="utf-8").strip()
    (args.out / "updates").mkdir(parents=True, exist_ok=True)
    (args.out / "downloads").mkdir(parents=True, exist_ok=True)
    packages = []
    for flavor, package in PACKAGES.items():
        directory = Path("app/build/outputs/apk") / flavor / "release"
        meta = json.loads((directory / "output-metadata.json").read_text(encoding="utf-8"))
        apk = meta["elements"][0]
        if meta["applicationId"] != package:
            raise SystemExit(f"Unexpected {flavor} application ID")
        version = apk["versionName"].removesuffix("-cs11")
        if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
            raise SystemExit("Expected numeric major.minor.patch")
        filename = f"NoMusic-{version}-{flavor}.apk"
        source = directory / apk["outputFile"]
        if signer(args.apksigner, source) != signer(args.apksigner, Path(f"artifacts/NoMusic-0.1.0-{flavor}.apk")):
            raise SystemExit(f"Signing certificate changed: {flavor}")
        target = args.out / "downloads" / filename
        shutil.copyfile(source, target)
        digest = hashlib.sha256(target.read_bytes()).hexdigest()
        manifest["packages"][package] = {
            "versionCode": apk["versionCode"], "versionName": apk["versionName"], "minSdk": 16,
            "size": target.stat().st_size, "sha256": digest, "url": f"{ORIGIN}/downloads/{filename}", "notes": notes,
        }
        packages.append((flavor, filename, digest))
    (args.out / "updates/latest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    links = "".join(f'<a href="/downloads/{name}">{label} <span>↓</span></a>'
                    for (flavor, name, _), label in zip(packages, ("标准版", "领克 CS11 兼容版")))
    logo = Path("docs/design/logo.svg").read_text(encoding="utf-8")
    (args.out / "index.html").write_text(f"""<!doctype html>
<html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>NoMusic 下载</title>
<style>
body{{background:#151412;color:#f4f0e8;font:17px system-ui,sans-serif;margin:0;padding:48px 24px}}
main{{max-width:520px;margin:auto}} header{{display:flex;align-items:center;gap:12px}} header svg{{width:56px;height:56px}}
h1{{font-size:46px;margin:0}} p{{color:#c2b8aa;line-height:1.7}}
a{{display:block;padding:16px 20px;background:#27231f;color:#f4f0e8;text-decoration:none;border:1px solid #494137;border-radius:12px;margin:12px 0}}
a:hover,a:focus-visible{{background:#3c3225}} a:focus-visible{{outline:2px solid #d9b982;outline-offset:3px}} span{{float:right;color:#d9b982}}
</style><main><header aria-label="NoMusic"><div aria-hidden="true">{logo}</div><h1>NoMusic</h1></header><p>车载 Emby 音乐播放器 · {version} · Android 4.1+</p>{links}
<p>升级时请选择与已安装版本相同的安装包。应用内也可在「设置 → 检查更新」查看新版本。</p>
</main></html>""", encoding="utf-8")
    print(f"Staged {len(packages)} verified APKs in {args.out}")


if __name__ == "__main__":
    main()
