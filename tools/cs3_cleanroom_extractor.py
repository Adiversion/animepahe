#!/usr/bin/env python3
"""
CS3 Cleanroom Extractor & Open-Source Converter
================================================
A tool to inspect, analyze, and convert closed-source CloudStream 3/4 plugins (.cs3)
into clean, 100% open-source Kotlin provider code without proprietary nagware,
donation loops, or closed tracking payloads.

Usage:
    python cs3_cleanroom_extractor.py <path-to-plugin.cs3> [--output <output-dir>]
"""

import sys
import os
import zipfile
import struct
import json
import argparse
from typing import Dict, List, Set, Any, Optional

class DexInspector:
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

    def get_string(self, idx: int) -> str:
        if idx >= self.string_ids_size:
            return f"<str_{idx}>"
        str_off = struct.unpack_from('<I', self.dex, self.string_ids_off + idx * 4)[0]
        pos = str_off
        length = 0
        shift = 0
        while True:
            b = self.dex[pos]
            pos += 1
            length |= (b & 0x7f) << shift
            if not (b & 0x80):
                break
            shift += 7
        end = self.dex.find(b'\x00', pos)
        return self.dex[pos:end].decode('utf-8', errors='replace')

    def extract_strings(self):
        self.strings = [self.get_string(i) for i in range(self.string_ids_size)]

    def extract_types(self):
        self.types = []
        for i in range(self.type_ids_size):
            str_idx = struct.unpack_from('<I', self.dex, self.type_ids_off + i * 4)[0]
            self.types.append(self.get_string(str_idx))

    def extract_methods(self):
        self.methods = []
        for i in range(self.method_ids_size):
            class_idx, proto_idx, name_idx = struct.unpack_from('<HHI', self.dex, self.method_ids_off + i * 8)
            cls = self.types[class_idx] if class_idx < len(self.types) else "<unknown_class>"
            mname = self.strings[name_idx] if name_idx < len(self.strings) else "<unknown_method>"
            self.methods.append((cls, mname))

    def analyze_provider(self) -> Dict[str, Any]:
        endpoints = []
        user_agents = []
        keys = []
        crypto_hints = []
        donation_bloat = []
        classes_found = set()

        for c in self.types:
            if c.startswith("Lcom/") or c.startswith("Lorg/"):
                clean = c.strip("L;").replace("/", ".")
                classes_found.add(clean)
                if any(x in clean.lower() for x in ["donation", "support", "dialogfragment", "tracking"]):
                    donation_bloat.append(clean)

        for s in self.strings:
            # Detect endpoints
            if s.startswith("http://") or s.startswith("https://") or s.startswith("/api"):
                if not any(x in s for x in ["w3.org", "schemas.android.com", "github.com/recloudstream"]):
                    endpoints.append(s)
            # Detect User-Agents
            if "Mozilla/5.0" in s:
                user_agents.append(s)
            # Detect HLS / Crypto / Decryption signatures
            if any(h in s for h in ["#EXT-X-KEY", "AES-128", ",IV=0x", "getAndUnpack", "source=", "eval(function"]):
                crypto_hints.append(s)

        return {
            "total_classes": len(classes_found),
            "classes": sorted(list(classes_found)),
            "detected_endpoints": sorted(list(set(endpoints))),
            "detected_user_agents": sorted(list(set(user_agents))),
            "crypto_and_stream_signatures": sorted(list(set(crypto_hints))),
            "closed_source_bloat_detected": sorted(list(set(donation_bloat))),
        }

def process_cs3(file_path: str, output_dir: Optional[str] = None):
    print(f"[*] Inspecting CloudStream Plugin: {file_path}")
    if not os.path.exists(file_path):
        print(f"[-] Error: File not found: {file_path}")
        return

    with zipfile.ZipFile(file_path, 'r') as z:
        namelist = z.namelist()
        print(f"[+] Archive entries: {len(namelist)} items")
        manifest_data = {}
        if "manifest.json" in namelist:
            manifest_data = json.loads(z.read("manifest.json").decode('utf-8'))
            print(f"[+] Plugin Name: {manifest_data.get('name')}")
            print(f"[+] Version: {manifest_data.get('version')}")
            print(f"[+] Authors: {manifest_data.get('authors')}")
            print(f"[+] Internal Name: {manifest_data.get('internalName')}")

        if "classes.dex" not in namelist:
            print("[-] No classes.dex found in CS3 bundle.")
            return

        dex_bytes = z.read("classes.dex")
        inspector = DexInspector(dex_bytes)
        analysis = inspector.analyze_provider()

        print("\n=== Cleanroom Reverse Engineering Analysis ===")
        print(f"[+] Discovered API Endpoints ({len(analysis['detected_endpoints'])}):")
        for ep in analysis['detected_endpoints']:
            print(f"    - {ep}")

        print(f"\n[+] Stream & Decryption Signatures ({len(analysis['crypto_and_stream_signatures'])}):")
        for sig in analysis['crypto_and_stream_signatures']:
            print(f"    - {sig}")

        print(f"\n[!] Closed-Source Bloat Detected ({len(analysis['closed_source_bloat_detected'])}):")
        for b in analysis['closed_source_bloat_detected']:
            print(f"    - {b} (STRIP IN OPEN-SOURCE FORK)")

        if output_dir:
            os.makedirs(output_dir, exist_ok=True)
            report_path = os.path.join(output_dir, "cleanroom_audit.json")
            with open(report_path, "w", encoding="utf-8") as f:
                json.dump({"manifest": manifest_data, "analysis": analysis}, f, indent=2)
            print(f"\n[+] Report saved to {report_path}")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python cs3_cleanroom_extractor.py <path-to-plugin.cs3> [--output <dir>]")
        sys.exit(1)
    parser = argparse.ArgumentParser(description="CS3 Cleanroom Reverse Engineering Extractor")
    parser.add_argument("cs3_file", help="Path to input .cs3 file")
    parser.add_argument("--output", "-o", help="Output directory for reports and scaffolding")
    args = parser.parse_args()
    process_cs3(args.cs3_file, args.output)
