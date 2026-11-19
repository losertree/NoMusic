#!/bin/sh
# Run on HostHatch as root with a staging directory created by mktemp.
set -eu
stage=${1:?Usage: publish.sh /tmp/nomusic-stage.XXXXXX}
root=/opt/nomusic-updates
site=$root/site
unit=/etc/systemd/system/nomusic-updates.service
test -f "$stage/server.py"
test -f "$stage/nomusic-updates.service"

# Verify the staged artifacts and refuse to replace an APK that may already be cached.
python3 - "$stage" "$site" <<'PY'
import hashlib
import json
import re
import sys
from pathlib import Path
from urllib.parse import urlsplit

stage, site = map(Path, sys.argv[1:])
manifest = json.loads((stage / "site/updates/latest.json").read_text(encoding="utf-8"))
assert manifest["schemaVersion"] == 1
assert set(manifest["packages"]) == {"com.nomusic.player", "com.netease.cloudmusic"}
active = site / "updates/latest.json"
previous = json.loads(active.read_text(encoding="utf-8")) if active.exists() else {"packages": {}}
for package, row in manifest["packages"].items():
    url = urlsplit(row["url"])
    assert url.scheme == "https" and url.netloc == "nomusic.losertree.de"
    assert re.fullmatch(r"/downloads/NoMusic-[0-9]+\.[0-9]+\.[0-9]+-(standard|cs11)\.apk", url.path)
    relative = url.path.lstrip("/")
    apk = stage / "site" / relative
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    assert apk.stat().st_size == row["size"] and digest == row["sha256"]
    old = previous["packages"].get(package)
    if old:
        assert old["versionCode"] < row["versionCode"] or old["versionCode"] == row["versionCode"] and old["sha256"] == digest, "Version must increase"
    published = site / relative
    if published.exists():
        assert hashlib.sha256(published.read_bytes()).hexdigest() == digest, f"APK is already published: {relative}"
PY

install -d -m 0755 "$root" "$site" "$site/updates" "$site/downloads"
for apk in "$stage"/site/downloads/*.apk; do
    name=${apk##*/}
    if [ ! -f "$site/downloads/$name" ]; then
        install -m 0644 "$apk" "$site/downloads/$name.new"
        mv -f "$site/downloads/$name.new" "$site/downloads/$name"
    fi
done

restart=false
if ! cmp -s "$stage/server.py" "$root/server.py"; then
    install -m 0644 "$stage/server.py" "$root/server.py.new"
    mv -f "$root/server.py.new" "$root/server.py"
    restart=true
fi
if ! cmp -s "$stage/nomusic-updates.service" "$unit"; then
    chmod 0644 "$stage/nomusic-updates.service"
    systemd-analyze verify "$stage/nomusic-updates.service"
    install -m 0644 "$stage/nomusic-updates.service" "$unit"
    systemctl daemon-reload
    restart=true
fi
systemctl enable --now nomusic-updates.service
if [ "$restart" = true ]; then systemctl restart nomusic-updates.service; fi
for attempt in 1 2 3 4 5; do
    if curl -fsS --max-time 2 -o /dev/null "http://127.0.0.1:5202/downloads/$name"; then break; fi
    if [ "$attempt" = 5 ]; then exit 1; fi
    sleep 1
done

install -m 0644 "$stage/site/index.html" "$site/index.html.new"
install -m 0644 "$stage/site/updates/latest.json" "$site/updates/latest.json.new"
mv -f "$site/index.html.new" "$site/index.html"
mv -f "$site/updates/latest.json.new" "$site/updates/latest.json"
curl -fsS --max-time 5 -o /dev/null http://127.0.0.1:5202/updates/latest.json
