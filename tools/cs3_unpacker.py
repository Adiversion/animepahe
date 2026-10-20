#!/usr/bin/env python3
"""
CloudStream 3 (.cs3) Extension Unpacker & Deep DEX Analyzer
===========================================================
A standalone reverse-engineering utility to inspect, unpack, and analyze
compiled CloudStream plugins (.cs3 / .zip archives containing classes.dex).

Features:
  - Direct local file or HTTP/HTTPS URL download and inspection.
  - Complete archive extraction (manifest.json, layouts, drawables, resources.arsc, classes.dex).
  - Deep DEX string pool parsing (MUTF-8 / ULEB128).
  - Method and class table extraction without external JVM / JADX dependencies.
  - Automatic detection of API endpoints, mirror domains, User-Agents, stream extractors, and crypto signatures.

Usage:
  python tools/cs3_unpacker.py <path-or-url-to-plugin.cs3> [--output <dir>] [--dump-strings]
"""

import sys
import os
import zipfile
import struct
import json
import argparse
import urllib.request
import re
from typing import Dict, List, Set, Any, Optional

def read_uleb128(data: bytes, pos: int):
    result = 0
    shift = 0
    while True:
        b = data[pos]
        pos += 1
        result |= (b & 0x7f) << shift
        if (b & 0x80) == 0:
            break
        shift += 7
    return result, pos

class DexParser:
    def __init__(self, dex_bytes: bytes):
        self.dex = dex_bytes
        self.parse_headers()
        self.extract_strings()
        self.extract_types()
        self.extract_methods()

    def parse_headers(self):
        magic = self.dex[:8]
        if not magic.startswith(b'dex\n'):
            raise ValueError("Invalid DEX magic header")
        self.string_ids_size, self.string_ids_off = struct.unpack_from('<II', self.dex, 56)
        self.type_ids_size, self.type_ids_off = struct.unpack_from('<II', self.dex, 64)
        self.proto_ids_size, self.proto_ids_off = struct.unpack_from('<II', self.dex, 72)
        self.field_ids_size, self.field_ids_off = struct.unpack_from('<II', self.dex, 80)
        self.method_ids_size, self.method_ids_off = struct.unpack_from('<II', self.dex, 88)
        self.class_defs_size, self.class_defs_off = struct.unpack_from('<II', self.dex, 96)

    def extract_strings(self):
        self.strings = []
        for i in range(self.string_ids_size):
            offset = struct.unpack_from('<I', self.dex, self.string_ids_off + i * 4)[0]
            pos = offset
            length = 0
            shift = 0
            while True:
                b = self.dex[pos]
                pos += 1
                length |= (b & 0x7f) << shift
                if (b & 0x80) == 0:
                    break
                shift += 7
            end = self.dex.find(b'\x00', pos)
            try:
                s = self.dex[pos:end].decode('utf-8')
            except UnicodeDecodeError:
                s = self.dex[pos:end].decode('latin1', errors='replace')
            self.strings.append(s)

    def extract_types(self):
        self.types = []
        for i in range(self.type_ids_size):
            str_idx = struct.unpack_from('<I', self.dex, self.type_ids_off + i * 4)[0]
            self.types.append(self.strings[str_idx] if str_idx < len(self.strings) else f"<type_{str_idx}>")

    def extract_methods(self):
        self.methods = []
        for i in range(self.method_ids_size):
            class_idx, proto_idx, name_idx = struct.unpack_from('<HHI', self.dex, self.method_ids_off + i * 8)
            cls = self.types[class_idx] if class_idx < len(self.types) else "<unknown_class>"
            mname = self.strings[name_idx] if name_idx < len(self.strings) else "<unknown_method>"
            self.methods.append((cls, mname))

    def analyze(self) -> Dict[str, Any]:
        endpoints = []
        user_agents = []
        crypto_signatures = []
        classes_found = set()
        domains = set()

        for c in self.types:
            if c.startswith("Lcom/") or c.startswith("Lorg/"):
                clean = c.strip("L;").replace("/", ".")
                classes_found.add(clean)

        for s in self.strings:
            if s.startswith("http://") or s.startswith("https://") or s.startswith("/api"):
                if not any(x in s for x in ["w3.org", "schemas.android.com", "github.com/recloudstream"]):
                    endpoints.append(s)
            m = re.findall(r'https?://(?:[a-zA-Z0-9-]+\.)+[a-zA-Z]{2,}', s)
            for d in m:
                if not any(x in d for x in ["w3.org", "schemas.android.com"]):
                    domains.add(d)
            if "Mozilla/5.0" in s:
                user_agents.append(s)
            if any(h in s for h in ["#EXT-X-KEY", "AES-128", ",IV=0x", "getAndUnpack", "eval(function", "kwik", "cf_clearance"]):
                crypto_signatures.append(s)

        return {
            "total_classes": len(classes_found),
            "classes": sorted(list(classes_found)),
            "detected_domains": sorted(list(domains)),
            "detected_endpoints": sorted(list(set(endpoints))),
            "detected_user_agents": sorted(list(set(user_agents))),
            "stream_and_bypass_signatures": sorted(list(set(crypto_signatures))),
        }

def process_file_or_url(target: str, output_dir: Optional[str] = None, dump_strings: bool = True):
    local_file = target
    temp_download = False

    if target.startswith("http://") or target.startswith("https://"):
        print(f"[*] Downloading remote .cs3 from: {target}")
        req = urllib.request.Request(target, headers={"User-Agent": "Mozilla/5.0"})
        local_file = "temp_downloaded_plugin.cs3"
        temp_download = True
        with urllib.request.urlopen(req) as resp, open(local_file, "wb") as f:
            f.write(resp.read())
        print(f"[+] Download complete ({os.path.getsize(local_file)} bytes)")

    if not os.path.exists(local_file):
        print(f"[-] File not found: {local_file}")
        return

    if not output_dir:
        base_name = os.path.splitext(os.path.basename(local_file))[0]
        if base_name == "temp_downloaded_plugin":
            base_name = "unpacked_plugin"
        output_dir = os.path.join(os.getcwd(), f"{base_name}_unpacked")

    os.makedirs(output_dir, exist_ok=True)
    print(f"[*] Unpacking {local_file} -> {output_dir}")

    with zipfile.ZipFile(local_file, 'r') as z:
        z.extractall(output_dir)
        namelist = z.namelist()
        print(f"[+] Extracted {len(namelist)} items:")
        for name in namelist[:15]:
            print(f"    - {name}")
        if len(namelist) > 15:
            print(f"    ... and {len(namelist) - 15} more files.")

        manifest_data = {}
        if "manifest.json" in namelist:
            manifest_data = json.loads(z.read("manifest.json").decode('utf-8', errors='ignore'))
            print("\n[+] Manifest Information:")
            for k, v in manifest_data.items():
                print(f"    - {k}: {v}")

        if "classes.dex" in namelist:
            dex_bytes = z.read("classes.dex")
            parser = DexParser(dex_bytes)
            analysis = parser.analyze()

            if dump_strings:
                strings_file = os.path.join(output_dir, "dex_strings.txt")
                with open(strings_file, "w", encoding="utf-8") as f:
                    for s in parser.strings:
                        f.write(s + "\n")
                print(f"\n[+] Dumped {len(parser.strings)} strings to {strings_file}")

            report_file = os.path.join(output_dir, "analysis_report.json")
            with open(report_file, "w", encoding="utf-8") as f:
                json.dump({"manifest": manifest_data, "analysis": analysis}, f, indent=2)
            print(f"[+] Saved analysis report to {report_file}")

            print("\n=== Analysis Summary ===")
            print(f" - Discovered Domains: {len(analysis['detected_domains'])}")
            for d in analysis['detected_domains'][:10]:
                print(f"     * {d}")
            print(f" - Discovered Endpoints: {len(analysis['detected_endpoints'])}")
            for ep in analysis['detected_endpoints'][:10]:
                print(f"     * {ep}")
            print(f" - Stream/Bypass Signatures: {len(analysis['stream_and_bypass_signatures'])}")
            for sig in analysis['stream_and_bypass_signatures'][:10]:
                print(f"     * {sig}")

    if temp_download and os.path.exists(local_file):
        os.remove(local_file)

    print(f"\n[+] Unpack and analysis completed successfully in {output_dir}")

def main():
    parser = argparse.ArgumentParser(description="CloudStream (.cs3) Extension Unpacker & DEX Inspector")
    parser.add_argument("target", help="Path or URL to .cs3 file")
    parser.add_argument("--output", "-o", help="Output directory")
    parser.add_argument("--no-strings", action="store_true", help="Do not dump all strings")
    args = parser.parse_args()

    process_file_or_url(args.target, args.output, dump_strings=not args.no_strings)

if __name__ == "__main__":
    main()
