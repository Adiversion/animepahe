import urllib.request
import time
import json
import ssl
import sys

sys.stdout.reconfigure(encoding='utf-8')

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

benchmarks = [
    {
        'name': 'SonyLIV Trending (apiv3.sonyliv.com)',
        'url': 'https://apiv3.sonyliv.com/AGL/4.8/A/ENG/WEB/IN/CT/TRAY/EXTCOLLECTION/9014011?from=0&to=15',
        'headers': {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)',
            'Origin': 'https://www.sonyliv.com',
            'Referer': 'https://www.sonyliv.com/'
        }
    },
    {
        'name': 'Apple TV+ Top 10 Movies (uts-api.itunes.apple.com)',
        'url': 'https://uts-api.itunes.apple.com/uts/v3/shelves/uts.col.ChartsMovies.tvs.sbd.4000?caller=web&sf=143467&v=72&pfm=web&locale=en-IN&utsk=6e3013c6d6fae3c2::::::',
        'headers': {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)',
            'Accept': 'application/json',
            'Origin': 'https://tv.apple.com',
            'Referer': 'https://tv.apple.com/'
        }
    },
    {
        'name': 'JioHotstar Movies BFF (www.hotstar.com)',
        'url': 'https://www.hotstar.com/api/internal/bff/v2/slugs/in/movies',
        'headers': {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)',
            'Accept': 'application/json, text/plain, */*',
            'x-hs-usertoken': 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ7XCJoSWRcIjpcImEzMDQ0ZjYxN2NkOTRlMmY5NjAxNmU3ZTZkYzFmMWJiXCIsXCJwSWRcIjpcIjE4M2YwNDgxYzI4MTQ3OTZhNzFiNzYzMTUzNThmZGJmXCIsXCJkd0hpZFwiOlwiNTcyNDAzZjM1OGM4MTlkNTZmZWY5NDliOWFmNTZlM2ZjODU3MmE2ZTBkN2JjMDZmYmY1MmFhYzc2YWY4NDc5ZlwiLFwiZHdQaWRcIjpcImFiZmZmMGNmMDNmN2JhNDY4MWUwODZlZDIxZTllMGQ4ZGI0MjBiODBhYjZjNWU3YmU5ZjgxMzFlMTlmYWY5NGJcIixcIm9sZEhpZFwiOlwiYTMwNDRmNjE3Y2Q5NGUyZjk2MDE2ZTdlNmRjMWYxYmJcIixcIm9sZFBpZFwiOlwiMTgzZjA0ODFjMjgxNDc5NmE3MWI3NjMxNTM1OGZkYmZcIixcImlzUGlpVXNlck1pZ3JhdGVkXCI6ZmFsc2UsXCJuYW1lXCI6XCJZb3VcIixcImlwXCI6XCIyNDAxOjQ5MDA6ODhiZDpiMzY2OjE0OTM6Mzg5OTo1OGIyOmNkMGZcIixcImNvdW50cnlDb2RlXCI6XCJpblwiLFwiY3VzdG9tZXJUeXBlXCI6XCJudVwiLFwidHlwZVwiOlwiZ3Vlc3RcIixcImlzRW1haWxWZXJpZmllZFwiOmZhbHNlLFwiaXNQaG9uZVZlcmlmaWVkXCI6ZmFsc2UsXCJkZXZpY2VJZFwiOlwiODkxMTllLTYyMjNlOS04ZTg5NjYtMzdhNTdjXCIsXCJwcm9maWxlXCI6XCJBRFVMVFwiLFwidmVyc2lvblwiOlwidjJcIixcInN1YnNjcmlwdGlvbnNcIjp7XCJpblwiOnt9fSxcImlzc3VlZEF0XCI6MTc5MjczNzM1Mzk0NixcIm1hdHVyaXR5TGV2ZWxcIjpcIkFcIixcImRwaWRcIjpcIjE4M2YwNDgxYzI4MTQ3OTZhNzFiNzYzMTUzNThmZGJmXCIsXCJzdFwiOjEsXCJkYXRhXCI6XCJDZ1FJQURvQUNnd0lBQ0lJa0FHYXRlUzdsalFLTGdnQVFpb0tLRUkzWTJVeE9HVTJObVUyTWpJME9EUTNPR1l6WTJZM01qTTFNamMyTmpWbU16a3ROV1J5UWpBPVwifSIsImlzcyI6IlVNIiwiZXhwIjoxNzkyODIzNzUzLCJqdGkiOiIyNDQxZTg1ZjEwNzQ0OGZkOWNmOTlmZjczYjQwMmZjMCIsImlhdCI6MTc5MjczNzM1MywiYXBwSWQiOiIiLCJ0ZW5hbnQiOiIiLCJ2ZXJzaW9uIjoiMV8wIiwiYXVkIjoidW1fYWNjZXNzIn0.TrOwZtwk1VHX4_i-C3280eyLfqDXX_PMcYAlPRoMXhc',
            'X-HS-Platform': 'web',
            'X-Country-Code': 'in'
        }
    },
    {
        'name': 'WaveFlix Search API (www.waveflix.online)',
        'url': 'https://www.waveflix.online/api/search?q=Singham',
        'headers': {'User-Agent': 'Mozilla/5.0'}
    },
    {
        'name': 'Cinemeta IMDb to TMDB (v3-cinemeta.strem.io)',
        'url': 'https://v3-cinemeta.strem.io/meta/series/tt11663228.json',
        'headers': {'User-Agent': 'Mozilla/5.0'}
    }
]

print("=== LATENCY BENCHMARK ===")
for b in benchmarks:
    req = urllib.request.Request(b['url'], headers=b['headers'])
    start = time.time()
    try:
        with urllib.request.urlopen(req, timeout=10, context=ctx) as resp:
            data = resp.read()
            elapsed_ms = int((time.time() - start) * 1000)
            print(f"[OK] {b['name']}: {elapsed_ms}ms (HTTP {resp.status}, {len(data):,} bytes)")
    except Exception as e:
        elapsed_ms = int((time.time() - start) * 1000)
        print(f"[ERR] {b['name']}: FAILED ({elapsed_ms}ms) -> {e}")
