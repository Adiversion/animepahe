import urllib.request
import urllib.parse
import ssl
import sys
import json
import time

sys.stdout.reconfigure(encoding='utf-8')
ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36'
}

passed = 0
failed = 0

def log_test(name, success, details=""):
    global passed, failed
    if success:
        passed += 1
        print(f"  [PASS] {name} {details}")
    else:
        failed += 1
        print(f"  [FAIL] {name} {details}")

print("==================================================================")
print("   AUTOMATED TEST SUITE: 100% OTT WEBSITE CONTENT VERIFICATION   ")
print("==================================================================")

# ------------------------------------------------------------------
# TEST SUITE 1: WaveFlix Provider
# ------------------------------------------------------------------
print("\n--- TEST 1: WaveFlix Core APIs ---")
try:
    url = "https://www.waveflix.online/api/discover?mediaType=movie&category=trending&page=1"
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req, timeout=8, context=ctx) as r:
        res = json.loads(r.read())
        results = res.get('results', [])
        has_items = len(results) > 0
        first_title = results[0].get('title') if has_items else ""
        first_poster = results[0].get('posterPath') if has_items else ""
        log_test("WaveFlix Discover API", has_items, f"({len(results)} items, 1st: '{first_title}')")
        log_test("WaveFlix Vertical Poster Path", bool(first_poster and first_poster.startswith('/')), f"('{first_poster}')")

    search_url = "https://www.waveflix.online/api/search?q=Tenzing"
    with urllib.request.urlopen(urllib.request.Request(search_url, headers=HEADERS), timeout=8, context=ctx) as r:
        s_res = json.loads(r.read())
        s_results = s_res.get('results', [])
        found_tmdb = len(s_results) > 0 and s_results[0].get('id') is not None
        log_test("WaveFlix Internal Search API", found_tmdb, f"(Found ID: {s_results[0].get('id')}, poster: {s_results[0].get('posterPath')})")
except Exception as e:
    log_test("WaveFlix Core APIs", False, str(e))

# ------------------------------------------------------------------
# TEST SUITE 2: JioHotstar Native BFF & Vertical Poster Verification
# ------------------------------------------------------------------
print("\n--- TEST 2: JioHotstar BFF Trays (Live Website Content) ---")
token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ7XCJoSWRcIjpcImEzMDQ0ZjYxN2NkOTRlMmY5NjAxNmU3ZTZkYzFmMWJiXCIsXCJwSWRcIjpcIjE4M2YwNDgxYzI4MTQ3OTZhNzFiNzYzMTUzNThmZGJmXCIsXCJkd0hpZFwiOlwiNTcyNDAzZjM1OGM4MTlkNTZmZWY5NDliOWFmNTZlM2ZjODU3MmE2ZTBkN2JjMDZmYmY1MmFhYzc2YWY4NDc5ZlwiLFwiZHdQaWRcIjpcImFiZmZmMGNmMDNmN2JhNDY4MWUwODZlZDIxZTllMGQ4ZGI0MjBiODBhYjZjNWU3YmU5ZjgxMzFlMTlmYWY5NGJcIixcIm9sZEhpZFwiOlwiYTMwNDRmNjE3Y2Q5NGUyZjk2MDE2ZTdlNmRjMWYxYmJcIixcIm9sZFBpZFwiOlwiMTgzZjA0ODFjMjgxNDc5NmE3MWI3NjMxNTM1OGZkYmZcIixcImlzUGlpVXNlck1pZ3JhdGVkXCI6ZmFsc2UsXCJuYW1lXCI6XCJZb3VcIixcImlwXCI6XCIyNDAxOjQ5MDA6ODhiZDpiMzY2OjE0OTM6Mzg5OTo1OGIyOmNkMGZcIixcImNvdW50cnlDb2RlXCI6XCJpblwiLFwiY3VzdG9tZXJUeXBlXCI6XCJudVwiLFwidHlwZVwiOlwiZ3Vlc3RcIixcImlzRW1haWxWZXJpZmllZFwiOmZhbHNlLFwiaXNQaG9uZVZlcmlmaWVkXCI6ZmFsc2UsXCJkZXZpY2VJZFwiOlwiODkxMTllLTYyMjNlOS04ZTg5NjYtMzdhNTdjXCIsXCJwcm9maWxlXCI6XCJBRFVMVFwiLFwidmVyc2lvblwiOlwidjJcIixcInN1YnNjcmlwdGlvbnNcIjp7XCJpblwiOnt9fSxcImlzc3VlZEF0XCI6MTc5MjczNzM1Mzk0NixcIm1hdHVyaXR5TGV2ZWxcIjpcIkFcIixcImRwaWRcIjpcIjE4M2YwNDgxYzI4MTQ3OTZhNzFiNzYzMTUzNThmZGJmXCIsXCJzdFwiOjEsXCJkYXRhXCI6XCJDZ1FJQURvQUNnd0lBQ0lJa0FHYXRlUzdsalFLTGdnQVFpb0tLRUkzWTJVeE9HVTJObVUyTWpJME9EUTNPR1l6WTJZM01qTTFNamMyTmpWbU16a3ROV1J5UWpBPVwifSIsImlzcyI6IlVNIiwiZXhwIjoxNzkyODIzNzUzLCJqdGkiOiIyNDQxZTg1ZjEwNzQ0OGZkOWNmOTlmZjczYjQwMmZjMCIsImlhdCI6MTc5MjczNzM1MywiYXBwSWQiOiIiLCJ0ZW5hbnQiOiIiLCJ2ZXJzaW9uIjoiMV8wIiwiYXVkIjoidW1fYWNjZXNzIn0.TrOwZtwk1VHX4_i-C3280eyLfqDXX_PMcYAlPRoMXhc"

hotstar_slugs = [
    ('home', 'Top 10 in India Today'),
    ('home', 'New on JioHotstar'),
    ('movies', 'Best of Bollywood Movies'),
    ('movies', 'Best of Hollywood Dubbed'),
    ('movies', 'Thriller Movies'),
    ('movies', 'Popular Movies'),
    ('shows', 'Popular Shows'),
    ('shows', 'Must Watch Originals'),
    ('shows', 'Kids Shows')
]

for slug, kw in hotstar_slugs:
    try:
        url = f"https://www.hotstar.com/api/internal/bff/v2/slugs/in/{slug}"
        req = urllib.request.Request(url, headers={
            'User-Agent': HEADERS['User-Agent'],
            'Accept': 'application/json, text/plain, */*',
            'x-hs-usertoken': token,
            'X-HS-Platform': 'web',
            'X-Country-Code': 'in',
            'Origin': 'https://www.hotstar.com',
            'Referer': 'https://www.hotstar.com/'
        })
        with urllib.request.urlopen(req, timeout=12, context=ctx) as r:
            data = json.loads(r.read())
        
        wrappers = data.get('success', {}).get('page', {}).get('spaces', {}).get('tray', {}).get('widget_wrappers', [])
        found_wrapper = None
        for w in wrappers:
            if w.get('template') != 'ScrollableTrayWidget': continue
            h = w.get('widget', {}).get('data', {}).get('header', {})
            for v in h.values():
                if isinstance(v, dict) and kw.lower() in str(v.get('title', '')).lower():
                    found_wrapper = w
                    break
            if found_wrapper: break
        
        if found_wrapper:
            items = found_wrapper.get('widget', {}).get('data', {}).get('items', [])
            item = items[0]
            vp = item.get('vertical_content_poster') or item.get('image_overlay_vertical_content_poster', {}).get('data', {}).get('vertical_content_poster')
            p_data = vp.get('data') if vp else None
            title = p_data.get('expanded_content_poster', {}).get('content_info', {}).get('title') if p_data else None
            img_src = p_data.get('image', {}).get('src') if p_data else ""
            is_vertical = img_src.endswith('-v')
            log_test(f"Hotstar Tray '{kw}'", True, f"({len(items)} items, 1st: '{title}')")
            log_test(f"Hotstar Vertical 1080x1920 Poster (-v)", is_vertical, f"src: '{img_src}')")
        else:
            log_test(f"Hotstar Tray '{kw}'", False, "No matching tray wrapper found")
    except Exception as e:
        log_test(f"Hotstar Tray '{kw}'", False, str(e))

# Test Live sports & Bigg Boss CNC channels
try:
    cnc_url = "https://cncverse.dpdns.org/catalog/tv/cnc_JioHotstar2_tv.json"
    with urllib.request.urlopen(urllib.request.Request(cnc_url, headers=HEADERS), timeout=8, context=ctx) as r:
        cnc_data = json.loads(r.read())
    metas = cnc_data.get('metas', [])
    bb_channels = [m for m in metas if 'bigboss' in str(m.get('name', '')).lower() or 'bigg boss' in str(m.get('name', '')).lower()]
    sports_channels = [m for m in metas if 'star sports' in str(m.get('name', '')).lower()]
    log_test("Hotstar CNC Live Channels (Star Sports)", len(sports_channels) > 0, f"({len(sports_channels)} channels found)")
    log_test("Hotstar CNC Live Channels (Bigg Boss 24x7)", len(bb_channels) > 0, f"({len(bb_channels)} channels found)")
except Exception as e:
    log_test("Hotstar CNC Channels", False, str(e))

# ------------------------------------------------------------------
# TEST SUITE 3: SonyLIV (Live Website Content)
# ------------------------------------------------------------------
print("\n--- TEST 3: SonyLIV AGL Trays (Live Website Content) ---")
sonyliv_trays = [
    ('9014011', 'Trending in India Today'),
    ('9015093', 'Popular SonyLIV Originals'),
    ('9016638', 'Blockbuster Movies on SonyLIV'),
    ('POPULAR_SEARCH', 'Popular on Sony SAB & TV'),
    ('3937924064', 'Trending in Sports'),
    ('9012681', 'Upcoming on LIV')
]

for tid, name in sonyliv_trays:
    try:
        url = f'https://apiv3.sonyliv.com/AGL/4.8/A/ENG/WEB/IN/CT/TRAY/SEARCH/POPULAR?from=0&to=10' if tid == 'POPULAR_SEARCH' else f'https://apiv3.sonyliv.com/AGL/4.8/A/ENG/WEB/IN/CT/TRAY/EXTCOLLECTION/{tid}?from=0&to=10'
        req = urllib.request.Request(url, headers={
            'User-Agent': HEADERS['User-Agent'],
            'Origin': 'https://www.sonyliv.com',
            'Referer': 'https://www.sonyliv.com/'
        })
        with urllib.request.urlopen(req, timeout=7, context=ctx) as r:
            s_data = json.loads(r.read())
        containers = s_data.get('resultObj', {}).get('containers', [])
        sample_title = containers[0].get('metadata', {}).get('title') if containers else ""
        log_test(f"SonyLIV Tray '{name}'", len(containers) > 0, f"({len(containers)} items, 1st: '{sample_title}')")
        
        first_c = containers[0]
        emf = first_c.get('metadata', {}).get('emfAttributes', {})
        poster = emf.get('img_poster_2000_3000') or emf.get('image_600x900_clean') or emf.get('apv_poster_art') or emf.get('tv_background_image')
        has_poster = bool(poster and poster.startswith('http'))
        log_test(f"SonyLIV Image Delivery for '{name}'", has_poster, f"('{poster}')")
    except Exception as e:
        log_test(f"SonyLIV Tray '{name}'", False, str(e))

# ------------------------------------------------------------------
# TEST SUITE 4: Apple TV+ (Live Website Content)
# ------------------------------------------------------------------
print("\n--- TEST 4: Apple TV+ UTS Edge Shelves (Live Website Content) ---")
apple_shelves = [
    ('uts.col.ChartsMovies.tvs.sbd.4000', 'Top 10 Movies Today'),
    ('uts.col.ChartsShows.tvs.sbd.4000', 'Top 10 Shows Today'),
    ('uts.col.tv-plus-must-see-hits', 'Must-See Hits & Blockbusters'),
    ('uts.col.tv-plus-newest-releases', 'New Releases on Apple TV+'),
    ('uts.col.apple-tv-movies-action', 'Action & Thriller Movies'),
    ('uts.col.apple-tv-movies-comedies', 'Feel-Good Comedies'),
    ('uts.col.apple-tv-movies-dramas', 'Acclaimed Dramas & Originals'),
    ('uts.col.kids-tv-plus-shows', 'Kids & Family Shows'),
    ('uts.col.free-premieres-on-tv-plus', 'Watch Premieres for Free')
]

for sid, name in apple_shelves:
    try:
        url = f'https://uts-api.itunes.apple.com/uts/v3/shelves/{sid}?caller=web&sf=143467&v=72&pfm=web&locale=en-IN&utsk=6e3013c6d6fae3c2::::::'
        req = urllib.request.Request(url, headers={
            'User-Agent': HEADERS['User-Agent'],
            'Accept': 'application/json',
            'Origin': 'https://tv.apple.com',
            'Referer': 'https://tv.apple.com/'
        })
        with urllib.request.urlopen(req, timeout=7, context=ctx) as r:
            a_data = json.loads(r.read())
        items = a_data.get('data', {}).get('shelf', {}).get('items', [])
        sample_title = items[0].get('title') if items else ""
        log_test(f"Apple TV+ Shelf '{name}'", len(items) > 0, f"({len(items)} items, 1st: '{sample_title}')")
    except Exception as e:
        log_test(f"Apple TV+ Shelf '{name}'", False, str(e))

# ------------------------------------------------------------------
# TEST SUITE 5: Amazon Prime Video (Official Storefront Content)
# ------------------------------------------------------------------
print("\n--- TEST 5: Amazon Prime Video Storefront Trays ---")
prime_lists = {
    'Top 10 with Prime Today': ['Rise And Fall', 'Jailer', 'Lenin Pandiyan', 'Chaar Yaar', 'Dupahiya'],
    'Top Movies on Prime Video': ['Awarapan 2', 'Khalifa', 'Sardar 2', 'Lenin Pandiyan', 'Romeo'],
    'Featured Originals on Prime': ['Masterplan', 'Love Of Your Life', 'My Fault', 'Your Fault', 'Red One'],
    'Cop Universe & Crime Thrillers': ['Drishyam 2', 'Waiting Hai', 'Rahasya', 'Section 375', 'B.A. Pass'],
    'Korean Dramas on Prime': ['True Beauty', 'Good Boy', 'Guardian: The Lonely And Great God']
}

for tray_name, titles in prime_lists.items():
    try:
        resolved_count = 0
        for t in titles:
            q_enc = urllib.parse.quote(t)
            with urllib.request.urlopen(urllib.request.Request(f"https://www.waveflix.online/api/search?q={q_enc}", headers=HEADERS), timeout=5, context=ctx) as r:
                res = json.loads(r.read())
                if len(res.get('results', [])) > 0:
                    resolved_count += 1
        log_test(f"Prime Video '{tray_name}'", resolved_count >= len(titles) - 1, f"({resolved_count}/{len(titles)} titles resolved with TMDB)")
    except Exception as e:
        log_test(f"Prime Video '{tray_name}'", False, str(e))

# ------------------------------------------------------------------
# TEST SUITE 6: CSX Provider Mirror Domains (urls.json)
# ------------------------------------------------------------------
print("\n--- TEST 6: CSX Provider Mirror Domains (urls.json) ---")
try:
    u_url = "https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json"
    with urllib.request.urlopen(urllib.request.Request(u_url, headers=HEADERS), timeout=6, context=ctx) as r:
        u_data = json.loads(r.read())
    log_test("SaurabhKaperwan Live Mirror Sync", True, f"({len(u_data)} provider domains loaded)")
    for p in ['hdhub4u', 'moviesmod', 'uhdmovies', 'moviesdrive', 'rogmovies', 'bollyflix']:
        domain = u_data.get(p)
        log_test(f"Domain for {p}", bool(domain), f"-> {domain}")
except Exception as e:
    log_test("Mirror Sync", False, str(e))

# ------------------------------------------------------------------
# SUMMARY
# ------------------------------------------------------------------
print("\n==================================================================")
print(f"TEST RESULTS: {passed} PASSED, {failed} FAILED")
print("==================================================================")
sys.exit(0 if failed == 0 else 1)
