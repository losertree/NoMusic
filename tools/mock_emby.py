"""Deterministic Emby contract fixture; no access to any real account.

Run: python tools/mock_emby.py
Android Emulator endpoint: http://10.0.2.2:18765
"""
import argparse
import io
import json
import math
import struct
import threading
import time
import wave
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

FAVORITES = set()
REPORTS = []
TRUNCATED = set()
LYRIC_REQUESTS = {}
DETAIL_REQUESTS = {}
PLAYLISTS = {"playlist-1": {"Id": "playlist-1", "Name": "Fixture Playlist", "Type": "Playlist", "entries": ["track-0", "track-1"]}}
LIBRARY_REQUESTS = []
BROWSE_HOLDS = {}
STREAM_FAILURES = {}
IMAGE_REQUESTS = {}
STREAM_REQUESTS = {}
STREAM_PROFILES = {}
STREAM_BEHAVIORS = {}
LOCK = threading.Lock()


def item(i, formats=False):
    record = {
        "Id": f"track-{i}", "Name": f"Track {i:03d}", "Type": "Audio",
        "Artists": ["Fixture Artist"], "ArtistItems": [{"Id": "artist-1", "Name": "Fixture Artist"}],
        "Album": "Fixture Album", "AlbumId": "album-1",
        "Genres": ["Even" if i % 2 == 0 else "Odd"], "GenreItems": [{"Id": "even" if i % 2 == 0 else "odd"}],
        "RunTimeTicks": 90 * 10_000_000,
        "UserData": {"IsFavorite": f"track-{i}" in FAVORITES},
    }
    if formats:
        codec, bitrate, rate, bits = [("flac", 850000, 44100, 16), ("mp3", 320000, 44100, 0),
                                      ("flac", 2300000, 96000, 24), ("aac", 128000, 44100, 0),
                                      ("", 0, 0, 0), ("dsd_lsbf", 5644800, 2822400, 1)][i % 6]
        if codec:
            stream = {"Type": "Audio", "Codec": codec, "BitRate": bitrate, "SampleRate": rate, "BitDepth": bits, "Channels": 2}
            record["MediaSources"] = [{"Container": codec, "MediaStreams": [stream]}]
    return record


def audio(start_seconds=0):
    samples = max(1, 90 - int(start_seconds)) * 8000
    cycle = b"".join(struct.pack("<h", int(300 * math.sin(i * 2 * math.pi * 200 / 8000))) for i in range(40))
    pcm = (cycle * ((samples + 39) // 40))[:samples * 2]
    output = io.BytesIO()
    with wave.open(output, "wb") as wav:
        wav.setparams((1, 2, 8000, 0, "NONE", "not compressed"))
        wav.writeframes(pcm)
    return output.getvalue()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"

    def log_message(self, *_):
        pass  # Never log request URLs, authorization headers, or passwords.

    def reply(self, code, body=None, mime="application/json", extra=None):
        data = json.dumps(body or {}).encode() if mime == "application/json" else body
        self.send_response(code)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Connection", "close")
        if code == 401:
            self.send_header("WWW-Authenticate", 'Emby realm="fixture"')
        for key, value in (extra or {}).items():
            self.send_header(key, value)
        self.end_headers()
        if self.command != "HEAD":
            try:
                # Old Android emulator slirp can drop a fast burst when the
                # host closes the socket before the guest drains its buffers.
                if len(data) > 2048:
                    for start in range(0, len(data), 8192):
                        self.wfile.write(data[start:start+8192])
                        self.wfile.flush()
                        time.sleep(0.002)
                    time.sleep(0.04)
                else:
                    self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                pass

    def dispatch(self):
        parsed = urlsplit(self.path)
        path = parsed.path.removeprefix("/emby")
        q = parse_qs(parsed.query)
        if path == "/fixture/status":
            with LOCK:
                return self.reply(200, {"Reports": list(REPORTS), "Favorites": sorted(FAVORITES), "LyricRequests": dict(LYRIC_REQUESTS), "DetailRequests": dict(DETAIL_REQUESTS), "LibraryRequests": list(LIBRARY_REQUESTS), "ImageRequests": dict(IMAGE_REQUESTS), "StreamRequests": dict(STREAM_REQUESTS)})
        if path == "/fixture/stream-count":
            with LOCK:
                count = STREAM_REQUESTS.get(q.get("id", [""])[0], 0)
            return self.reply(200, {"Count": count})
        if path == "/fixture/stream-profile":
            with LOCK:
                return self.reply(200, STREAM_PROFILES.get(q.get("id", [""])[0], {}))
        if path == "/fixture/browse-count":
            prefix = "/Users/" + q.get("user", [""])[0] + "/"
            search = q.get("term")
            with LOCK:
                count = sum(row["path"].startswith(prefix) and (search is None or row["query"].get("SearchTerm") == search) for row in LIBRARY_REQUESTS)
            return self.reply(200, {"Count": count})
        if path == "/fixture/browse-hold":
            term = q.get("term", [""])[0].lower()
            with LOCK:
                if q.get("hold") == ["1"]:
                    BROWSE_HOLDS.setdefault(term, threading.Event())
                else:
                    waiting = BROWSE_HOLDS.pop(term, None)
                    if waiting is not None:
                        waiting.set()
            return self.reply(200)
        if path == "/fixture/stream-failure":
            with LOCK:
                STREAM_FAILURES[q.get("id", [""])[0]] = {"remaining": int(q.get("count", ["0"])[0]), "status": int(q.get("status", ["503"])[0])}
            return self.reply(200)
        if path == "/fixture/stream-behavior":
            with LOCK:
                STREAM_BEHAVIORS[q.get("id", [""])[0]] = {"delay": float(q.get("delay", ["0"])[0]), "truncate": int(q.get("truncate", ["0"])[0]), "source_status": int(q.get("source_status", ["0"])[0])}
            return self.reply(200)
        if path in ("/fixture/401", "/fixture/403"):
            return self.reply(int(path.rsplit("/", 1)[1]))
        if path == "/fixture/truncated":
            nonce = q.get("nonce", [""])[0]
            with LOCK:
                first = nonce not in TRUNCATED
                TRUNCATED.add(nonce)
            if first:
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", "100")
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(b"{")
                return
            return self.reply(200, {"Retried": True})
        if path == "/Users/AuthenticateByName" and self.command == "POST":
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
            if body != {"Username": "fixture", "Pw": "fixture"}:
                return self.reply(401)
            return self.reply(200, {"AccessToken": "fixture-token", "User": {"Id": "fixture-user", "Name": "fixture"}})
        if self.headers.get("X-Emby-Token") != "fixture-token" and q.get("api_key") != ["fixture-token"]:
            return self.reply(401)
        if path == "/Playlists" and self.command == "POST":
            with LOCK:
                identifier = f"playlist-test-{time.time_ns()}"
                PLAYLISTS[identifier] = {"Id": identifier, "Name": q.get("Name", [""])[0], "Type": "Playlist", "entries": q.get("Ids", [""])[0].split(",") if q.get("Ids", [""])[0] else []}
            return self.reply(200, {"Id": identifier})
        if path.startswith("/Items/") and self.command in ("POST", "DELETE"):
            identifier = path.split("/")[-1]
            if identifier not in PLAYLISTS:
                return self.reply(404)
            with LOCK:
                if self.command == "DELETE":
                    del PLAYLISTS[identifier]
                else:
                    body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
                    PLAYLISTS[identifier]["Name"] = body["Name"]
            return self.reply(204)
        if path.startswith("/Playlists/") and path.endswith("/Items") and self.command in ("POST", "DELETE"):
            identifier = path.split("/")[2]
            with LOCK:
                if identifier not in PLAYLISTS:
                    return self.reply(404)
                entries = PLAYLISTS[identifier]["entries"]
                if self.command == "POST":
                    entries.extend(q.get("Ids", [""])[0].split(","))
                else:
                    removed = sorted((int(v.removeprefix("entry-")) for v in q.get("EntryIds", [""])[0].split(",")), reverse=True)
                    for i in removed:
                        if i < len(entries):
                            entries.pop(i)
            return self.reply(204)
        if path.endswith("/Views"):
            return self.reply(200, {"Items": [{"Id": "music-root", "Name": "Music", "Type": "CollectionFolder", "CollectionType": "music"}]})
        if path == "/MusicGenres":
            return self.reply(200, {"Items": [{"Id": "even", "Name": "Even", "Type": "MusicGenre"}, {"Id": "odd", "Name": "Odd", "Type": "MusicGenre"}]})
        if path.startswith("/Sessions/Playing"):
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
            with LOCK:
                REPORTS.append({"Event": path, "ItemId": body.get("ItemId"), "PositionTicks": body.get("PositionTicks"), "IsPaused": body.get("IsPaused")})
            return self.reply(200)
        if "/FavoriteItems/" in path:
            track = path.rsplit("/", 1)[1]
            with LOCK:
                if self.command == "POST":
                    FAVORITES.add(track)
                elif self.command == "DELETE":
                    FAVORITES.discard(track)
            return self.reply(200, {"IsFavorite": track in FAVORITES})
        if "/Audio/" in path:
            identifier = path.split("/")[2]
            with LOCK:
                STREAM_REQUESTS[identifier] = STREAM_REQUESTS.get(identifier, 0) + 1
                original = q.get("Static") == ["true"]
                STREAM_PROFILES[identifier] = {"Original": original, "Bitrate": int(q.get("AudioBitRate", ["0"])[0]),
                                               "MaxBitrate": int(q.get("MaxStreamingBitrate", ["0"])[0]),
                                               "StartTicks": int(q.get("StartTimeTicks", ["0"])[0])}
                failure = STREAM_FAILURES.get(identifier)
                if failure and failure["remaining"] > 0:
                    failure["remaining"] -= 1
                    return self.reply(failure["status"])
                behavior = STREAM_BEHAVIORS.get(identifier, {})
                if original and behavior.get("source_status"):
                    return self.reply(behavior["source_status"])
                delay, truncated = behavior.get("delay", 0), behavior.get("truncate", 0) > 0
                if truncated:
                    behavior["truncate"] -= 1
            if delay:
                time.sleep(delay)
            if truncated:
                self.send_response(200)
                self.send_header("Content-Type", "audio/wav")
                self.send_header("Content-Length", str(len(audio())))
                self.send_header("Connection", "close")
                self.end_headers()
                try:
                    self.wfile.write(audio()[:1024])
                except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                    pass
                return
            start = int(q.get("StartTimeTicks", ["0"])[0]) / 10_000_000
            data = audio(start)
            headers = {"Accept-Ranges": "bytes"}
            status = 200
            header = self.headers.get("Range", "")
            if header.startswith("bytes="):
                span = header[6:].split("-", 1)
                begin = int(span[0] or 0)
                end = min(len(data)-1, int(span[1]) if span[1] else len(data)-1)
                if begin >= len(data):
                    return self.reply(416)
                headers["Content-Range"] = f"bytes {begin}-{end}/{len(data)}"
                data = data[begin:end+1]
                status = 206
            return self.reply(status, data, "audio/wav", headers)
        if path.endswith("/Lyrics"):
            track = path.split("/")[-2]
            with LOCK:
                count = LYRIC_REQUESTS.get(track, 0) + 1
                LYRIC_REQUESTS[track] = count
            if track.startswith("lyric-delayed-"):
                time.sleep(0.4)
            if track.startswith("lyric-scroll-"):
                return self.reply(200, {"Lyrics": [{"Text": f"Original line {i:02d}", "Translation": f"翻译第{i:02d}行", "StartTicks": i * 10_000_000} for i in range(80)]})
            if track.startswith("lyric-embedded-empty-api-"):
                return self.reply(200, {"Lyrics": []})
            if track.startswith("lyric-embedded-invalid-api-"):
                return self.reply(200, b"{", "text/plain")
            if track.startswith("lyric-embedded-"):
                return self.reply(404)
            if track.startswith("lyric-404-"):
                return self.reply(404)
            if track.startswith("lyric-empty-"):
                return self.reply(200, {"Lyrics": []})
            if (track.startswith("lyric-flaky-") and count <= 3) or (track.startswith("lyric-refresh-fails-") and count > 1):
                return self.reply(503)
            if track.startswith("lyric-invalid-"):
                return self.reply(200, b"{", "text/plain")
            if track.startswith("lyric-text-"):
                return self.reply(200, {"Text": "Plain lyric text"})
            field = "Lines" if track.startswith("lyric-lines-") else "LyricLines" if track.startswith("lyric-lyriclines-") else "Lyrics"
            text = f"Updated lyric {count}" if track.startswith("lyric-refresh-") else "Fixture lyric line 1"
            return self.reply(200, {field: [{"Text": text, "StartTicks": 0}, {"Text": "Fixture lyric line 2", "StartTicks": 100_000_000}]})
        if path.startswith("/Users/") and "/Items/" in path:
            track = path.rsplit("/", 1)[-1]
            with LOCK:
                count = DETAIL_REQUESTS.get(track, 0) + 1
                DETAIL_REQUESTS[track] = count
            if track in PLAYLISTS:
                return self.reply(200, {k: v for k, v in PLAYLISTS[track].items() if k != "entries"})
            if track.startswith("track-"):
                record = item(int(track.split("-")[1]), True)
                for source in record.get("MediaSources", []):
                    source["Size"] = len(audio())
                return self.reply(200, record)
            if track.startswith("lyric-embedded-flaky-") and count <= 3:
                return self.reply(503)
            streams = []
            if track.startswith("lyric-embedded-"):
                streams = [{"Type": "Audio", "Codec": "flac", "Extradata": "Not lyrics"},
                           {"Type": "Subtitle", "Codec": "text", "Title": "Lyrics", "IsExternal": False,
                            "Extradata": "[00:01.00]内嵌歌词第一行\n[00:02.00]第二行"}]
            if track.startswith("lyric-embedded-source-"):
                return self.reply(200, {"Id": track, "MediaSources": [{"MediaStreams": streams}]})
            return self.reply(200, {"Id": track, "MediaStreams": streams})
        if "/Images/" in path:
            identifier = path.split("/")[2]
            with LOCK:
                IMAGE_REQUESTS[identifier] = count = IMAGE_REQUESTS.get(identifier, 0) + 1
            if identifier.startswith("art-retry-") and count == 1:
                return self.reply(503)
            if identifier.startswith("art-exhaust-") and count <= 3:
                return self.reply(503)
            if identifier.startswith(("art-image-", "art-song-", "art-retry-", "art-exhaust-")):
                def chunk(kind, data):
                    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
                png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 8, 8, 8, 2, 0, 0, 0))
                png += chunk(b"IDAT", zlib.compress((b"\x00" + b"\xd0\x60\x30" * 8) * 8)) + chunk(b"IEND", b"")
                return self.reply(200, png, "image/png")
            return self.reply(404)
        if path.endswith("/Items") or path == "/Artists":
            with LOCK:
                LIBRARY_REQUESTS.append({"path": path, "query": q})
            kind = q.get("IncludeItemTypes", ["Audio"])[0]
            formats = "MediaSources" in q.get("Fields", [""])[0].split(",")
            if kind == "MusicAlbum":
                records = [{"Id": "album-1", "Name": "Fixture Album", "Type": kind}]
            elif kind == "MusicArtist" or path == "/Artists":
                records = [{"Id": "artist-1", "Name": "Fixture Artist", "Type": "MusicArtist"}]
            elif kind == "Playlist":
                with LOCK:
                    records = [{k: v for k, v in p.items() if k != "entries"} for p in PLAYLISTS.values()]
            else:
                records = [item(i, formats) for i in range(105)]
            if q.get("ParentId", [""])[0].startswith("art-album-"):
                suffix = q["ParentId"][0].removeprefix("art-album-")
                records = [{"Id": "art-song-" + suffix, "Name": "Song artwork", "Type": "Audio", "ImageTags": {"Primary": "song-tag"}}]
            if kind == "Audio,MusicAlbum,MusicArtist":
                records += [{"Id": "album-1", "Name": "Fixture Album", "Type": "MusicAlbum"}, {"Id": "artist-1", "Name": "Fixture Artist", "Type": "MusicArtist"}]
            if q.get("Recursive") == ["false"] and q.get("ParentId") == ["music-root"]:
                records = [{"Id": "folder-1", "Name": "Folder", "Type": "Folder"}] + records[:2]
            elif q.get("ParentId") or q.get("ArtistIds"):
                records = records[:2]
            if path.startswith("/Playlists/"):
                identifier = path.split("/")[2]
                with LOCK:
                    if identifier not in PLAYLISTS:
                        return self.reply(404)
                    records = [dict(item(int(track.split("-")[1]), formats), PlaylistItemId=f"entry-{i}") for i, track in enumerate(PLAYLISTS[identifier]["entries"])]
            if q.get("GenreIds"):
                genre = q["GenreIds"][0]
                records = [record for record in records if record.get("GenreItems", [{}])[0].get("Id") == genre]
            if q.get("Filters") == ["IsFavorite"]:
                records = [x for x in records if x["Id"] in FAVORITES]
            if q.get("Filters") == ["IsPlayed"]:
                records = records[:3]
            term = q.get("SearchTerm", [""])[0].lower()
            if formats:
                with LOCK:
                    hold = BROWSE_HOLDS.get(term)
                if hold is not None:
                    hold.wait(10)
            if term:
                records = [x for x in records if term in (x["Name"] + " " + " ".join(x.get("Artists", []))).lower()]
            if q.get("SortBy") == ["Runtime"]:
                records.sort(key=lambda record: record.get("RunTimeTicks", 0), reverse=q.get("SortOrder") == ["Descending"])
            elif q.get("SortBy") == ["DateCreated"]:
                records.sort(key=lambda record: record["Id"], reverse=q.get("SortOrder") == ["Descending"])
            elif q.get("SortBy") == ["SortName"]:
                records.sort(key=lambda record: record["Name"], reverse=q.get("SortOrder") == ["Descending"])
            count = len(records)
            start = int(q.get("StartIndex", [0])[0])
            limit = int(q.get("Limit", [100])[0])
            return self.reply(200, {"Items": records[start:start+limit], "TotalRecordCount": count})
        self.reply(404)

    do_GET = dispatch
    do_HEAD = dispatch
    do_POST = dispatch
    do_DELETE = dispatch


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18765)
    args = parser.parse_args()
    print(f"Emby fixture listening on 127.0.0.1:{args.port}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
