#!/usr/bin/env python3
"""
Automated upstream synchronizer for CloudStream plugins.
Syncs 'CNC Verse Mobile' (NetMirror) directly from NivinCNC's builds/plugins.json
into this repository's plugins.json.
"""

import sys
import os
import json
import urllib.request

DEFAULT_PLUGINS_PATH = "builds/plugins.json"
UPSTREAM_PLUGINS_URL = (
    "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/builds/plugins.json"
)

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

    # Check if local already has this version
    existing_entry = next((p for p in local_plugins if p.get("name") == "CNC Verse Mobile"), None)
    if existing_entry and existing_entry.get("version") == upstream_version:
        print(f"CNC Verse Mobile is already at latest version ({upstream_version}).")
    else:
        prev_ver = existing_entry.get("version") if existing_entry else "none"
        print(f"Updating CNC Verse Mobile: v{prev_ver} -> v{upstream_version}")

    # 4. Merge: replace or append CNC Verse Mobile
    merged = [p for p in local_plugins if p.get("name") != "CNC Verse Mobile"]
    merged.append(target_entry)

    # 5. Write back to plugins.json
    os.makedirs(os.path.dirname(os.path.abspath(target_plugins_path)), exist_ok=True)
    with open(target_plugins_path, "w", encoding="utf-8") as f:
        json.dump(merged, f, indent=2, ensure_ascii=False)
        f.write("\n")

    print(f"Successfully saved {len(merged)} plugins to {target_plugins_path}.")
    return True

if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_PLUGINS_PATH
    sync_plugins(target)
