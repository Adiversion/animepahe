#!/usr/bin/env python3
"""
Automated upstream synchronizer and patcher for CloudStream plugins.
Syncs 'CNC Verse Mobile' (NetMirror) directly from NivinCNC's builds/plugins.json
into this repository's plugins.json, and auto-patches the cookie crash.
"""

import sys
import os
import json
import urllib.request
import zipfile
import io
import hashlib
import zlib
import struct

DEFAULT_PLUGINS_PATH = "builds/plugins.json"
UPSTREAM_PLUGINS_URL = (
    "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/builds/plugins.json"
)
UPSTREAM_CS3_URL = (
    "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/builds/CNC%20Verse%20Mobile.cs3"
)

def patch_cncverse_cs3(cs3_path):
    if not os.path.exists(cs3_path):
        return False
    try:
        with open(cs3_path, "rb") as f:
            data = f.read()
        zf = zipfile.ZipFile(io.BytesIO(data))
        if "classes.dex" not in zf.namelist():
            return False
        dex = bytearray(zf.read("classes.dex"))

        # Find string index for 'Failed to verify cookie'
        string_ids_size = struct.unpack_from('<I', dex, 0x38)[0]
        string_ids_off = struct.unpack_from('<I', dex, 0x3c)[0]
        target_str_idx = -1
        for s_idx in range(string_ids_size):
            off = struct.unpack_from('<I', dex, string_ids_off + s_idx * 4)[0]
            if dex[off:off+40].find(b'Failed to verify cookie') != -1:
                target_str_idx = s_idx
                break

        if target_str_idx == -1:
            target_str_idx = 552

        lo = target_str_idx & 0xff
        hi = (target_str_idx >> 8) & 0xff
        patched = 0
        for i in range(len(dex) - 15):
            if dex[i] == 0x1a and dex[i+2] == lo and dex[i+3] == hi:
                for j in range(i, min(len(dex) - 2, i + 15)):
                    if dex[j] == 0x27:  # throw opcode
                        dex[j] = 0x00      # nop
                        dex[j+1] = 0x00    # nop
                        patched += 1

        if patched > 0:
            print(f"Patched {patched} cookie throw opcodes in CNC Verse Mobile classes.dex")
            dex[12:32] = hashlib.sha1(dex[32:]).digest()
            new_adler = zlib.adler32(dex[12:]) & 0xffffffff
            struct.pack_into("<I", dex, 8, new_adler)

            out_bio = io.BytesIO()
            with zipfile.ZipFile(out_bio, "w", compression=zipfile.ZIP_DEFLATED) as out_zf:
                for item in zf.infolist():
                    if item.filename == "classes.dex":
                        out_zf.writestr(item, bytes(dex))
                    else:
                        out_zf.writestr(item, zf.read(item.filename))

            with open(cs3_path, "wb") as f:
                f.write(out_bio.getvalue())
            print(f"Successfully saved patched {cs3_path}")
            return True
    except Exception as e:
        print(f"Warning: could not patch CNC Verse cs3: {e}")
    return False

def sync_plugins(target_plugins_path=DEFAULT_PLUGINS_PATH):
    print(f"Target plugins file: {target_plugins_path}")

    # 1. Read existing local plugins.json
    local_plugins = []
    if os.path.exists(target_plugins_path):
        try:
            with open(target_plugins_path, "r", encoding="utf-8") as f:
                local_plugins = json.load(f)
            print(f"Read {len(local_plugins)} existing plugins from {target_plugins_path}")
        except Exception as e:
            print(f"Warning: could not read existing plugins: {e}")
            local_plugins = []

    # 2. Fetch upstream plugins.json
    print(f"Fetching upstream plugins from {UPSTREAM_PLUGINS_URL}...")
    req = urllib.request.Request(
        UPSTREAM_PLUGINS_URL,
        headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}
    )
    with urllib.request.urlopen(req, timeout=15) as resp:
        upstream_plugins = json.loads(resp.read().decode("utf-8"))

    # 3. Locate CNC Verse Mobile
    target_entry = None
    for p in upstream_plugins:
        if p.get("name") == "CNC Verse Mobile":
            target_entry = p
            break

    if not target_entry:
        print("Error: 'CNC Verse Mobile' not found in upstream plugins.json!")
        sys.exit(1)

    upstream_version = target_entry.get("version", 0)
    print(f"Upstream CNC Verse Mobile: version {upstream_version}")

    # 4. Merge: replace or append CNC Verse Mobile
    merged = [p for p in local_plugins if p.get("name") != "CNC Verse Mobile"]
    merged.append(target_entry)

    # 5. Write back to plugins.json
    os.makedirs(os.path.dirname(os.path.abspath(target_plugins_path)), exist_ok=True)
    with open(target_plugins_path, "w", encoding="utf-8") as f:
        json.dump(merged, f, indent=2, ensure_ascii=False)
        f.write("\n")

    print(f"Successfully saved {len(merged)} plugins to {target_plugins_path}.")

    # 6. Auto-download and patch CNC Verse Mobile.cs3 if directory exists
    builds_dir = os.path.dirname(os.path.abspath(target_plugins_path))
    cs3_path = os.path.join(builds_dir, "CNC Verse Mobile.cs3")
    if not os.path.exists(cs3_path):
        try:
            print(f"Downloading CNC Verse Mobile.cs3 to {cs3_path}...")
            cs3_req = urllib.request.Request(UPSTREAM_CS3_URL, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(cs3_req, timeout=15) as r:
                with open(cs3_path, "wb") as f:
                    f.write(r.read())
        except Exception as e:
            print(f"Warning: could not download CNC Verse Mobile.cs3: {e}")

    if os.path.exists(cs3_path):
        patch_cncverse_cs3(cs3_path)

    return True

if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_PLUGINS_PATH
    sync_plugins(target)
