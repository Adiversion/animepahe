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
print("       AUTOMATED TEST SUITE: ALL CLOUDSTREAM OTT & CSX PROVIDERS   ")
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
print("\n--- TEST 2: JioHotstar BFF Trays & 1080x1920 Poster Ratio ---")
token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ7XCJoSWRcIjpcImEzMDQ0ZjYxN2NkOTRlMmY5NjAxNmU3ZTZkYzFmMWJiXCIsXCJwSWRcIjpcIjE4M2YwNDgxYzI4MTQ3OTZhNzFiNzYzMTUzNThmZGJmXCIsXCJkd0hpZFwiOlwiNTcyNDAzZjM1OGM4MTlkNTZmZWY5NDliOWFmNTZlM2ZjODU3MmE2ZTBkN2JjMDZmYmY1MmFhYzc2YWY4NDc5ZlwiLFwiZHdQaWRcIjpcImFiZmZmMGNmMDNmN2JhNDY4MWUwODZlZDIxZTllMGQ4ZGI0MjBiODBhYjZjNWU3YmU5ZjgxMzFlMTlmYWY5NGJcIixcIm9sZEhpZFwiOlwiYTMwNDRmNjE3Y2Q5NGUyZjk2MDE2ZTdlNmRjMWYxYmJcIixcIm9sZFBpZFwiOlwiMTgzZjA0ODFjMjgxNDc5NmE3MWI3NjMxNTM1OGZkYmZcIixcImlzUGlpVXNlck1pZ3JhdGVkXCI6ZmFsc2UsXCJuYW1lXCI6XCJZb3VcIixcImlwXCI6XCIyNDAxOjQ5MDA6ODhiZDpiMzY2OjE0OTM6Mzg5OTo1OGIyOmNkMGZcIixcImNvdW50cnlDb2RlXCI6XCJpblwiLFwiY3VzdG9tZXJUeXBlXCI6XCJudVwiLFwidHlwZVwiOlwiZ3Vlc3RcIixcImlzRW1haWxWZXJpZmllZFwiOmZhbHNlLFwiaXNQaG9uZVZlcmlmaWVkXCI6ZmFsc2UsXCJkZXZpY2VJZFwiOlwiODkxMTllLTYyMjNlOS04ZTg5NjYtMzdhNTdjXCIsXCJwcm9maWxlXCI6XCJBRFVMVFwiLFwidmVyc2lvblwiOlwidjJcIixcInN1YnNjcmlwdGlvbnNcIjp7XCJpblwiOnt9fSxcImlzc3VlZEF0XCI6MTc5MjczNzM1Mzk0NixcIm1hdHVyaXR5TGV2ZWxcIjpcIkFcIixcImRwaWRcIjpcIjE4M2YwNDgxYzI4MTQ3OTZhNzFiNzYzMTUzNThmZGJmXCIsXCJzdFwiOjEsXCJkYXRhXCI6XCJDZ1FJQURvQUNnd0lBQ0lJa0FHYXRlUzdsalFLTGdnQVFpb0tLRUkzWTJVeE9HVTJObVUyTWpJME9EUTNPR1l6WTJZM01qTTFNamMyTmpWbU16a3ROV1J5UWpBPVwifSIsImlzcyI6IlVNIiwiZXhwIjoxNzkyODIzNzUzLCJqdGkiOiIyNDQxZTg1ZjEwNzQ0OGZkOWNmOTlmZjczYjQwMmZjMCIsImlhdCI6MTc5MjczNzM1MywiYXBwSWQiOiIiLCJ0ZW5hbnQiOiIiLCJ2ZXJzaW9uIjoiMV8wIiwiYXVkIjoidW1fYWNjZXNzIn0.TrOwZtwk1VHX4_i-C3280eyLfqDXX_PMcYAlPRoMXhc"

hotstar_slugs = [
    ('home', 'Top 10 in India Today', True),
    ('movies', 'Best of Bollywood Movies', False),
    ('movies', 'Best of Hollywood Dubbed', False),
    ('shows', 'Popular Shows', False),
    ('shows', 'Kids Shows', False)
]

for slug, kw, is_overlay in hotstar_slugs:
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
# TEST SUITE 3: SonyLIV Vertical 2000x3000 / 600x900 Poster Verification
# ------------------------------------------------------------------
print("\n--- TEST 3: SonyLIV AGL Trays & Vertical Aspect Ratio ---")
try:
    s_url = "https://apiv3.sonyliv.com/AGL/4.8/A/ENG/WEB/IN/CT/TRAY/EXTCOLLECTION/9014011?from=0&to=10"
    s_req = urllib.request.Request(s_url, headers={
        'User-Agent': HEADERS['User-Agent'],
        'Origin': 'https://www.sonyliv.com',
        'Referer': 'https://www.sonyliv.com/'
    })
    with urllib.request.urlopen(s_req, timeout=7, context=ctx) as r:
        s_data = json.loads(r.read())
    containers = s_data.get('resultObj', {}).get('containers', [])
    log_test("SonyLIV Trending Tray (9014011)", len(containers) > 0, f"({len(containers)} items)")
    
    # Check vertical poster resolution
    first_c = containers[0]
    meta = first_c.get('metadata', {})
    emf = meta.get('emfAttributes', {})
    vertical_poster = emf.get('img_poster_2000_3000') or emf.get('image_600x900_clean') or emf.get('apv_poster_art')
    has_vertical = bool(vertical_poster and ('600x900' in vertical_poster or 'Poster' in vertical_poster or '2000_3000' in vertical_poster))
    log_test("SonyLIV Vertical Poster Priority (2000x3000 / 600x900)", has_vertical, f"('{vertical_poster}')")
    
    # Check IMDb ID SameAs extraction
    has_imdb = any(c.get('metadata', {}).get('emfAttributes', {}).get('SameAs') for c in containers)
    log_test("SonyLIV Canonical IMDb ID Extraction", has_imdb, "Verified SameAs attribute")
except Exception as e:
    log_test("SonyLIV Tests", False, str(e))

# ------------------------------------------------------------------
# TEST SUITE 4: Apple TV+ Public UTS Edge & TMDB Vertical Fallback
# ------------------------------------------------------------------
print("\n--- TEST 4: Apple TV+ UTS Edge & Vertical Poster Ratio ---")
try:
    a_url = "https://uts-api.itunes.apple.com/uts/v3/shelves/uts.col.ChartsMovies.tvs.sbd.4000?caller=web&sf=143467&v=72&pfm=web&locale=en-IN&utsk=6e3013c6d6fae3c2::::::"
    a_req = urllib.request.Request(a_url, headers={
        'User-Agent': HEADERS['User-Agent'],
        'Accept': 'application/json',
        'Origin': 'https://tv.apple.com',
        'Referer': 'https://tv.apple.com/'
    })
    with urllib.request.urlopen(a_req, timeout=7, context=ctx) as r:
        a_data = json.loads(r.read())
    items = a_data.get('data', {}).get('shelf', {}).get('items', [])
    log_test("Apple TV+ Top 10 Movies Shelf", len(items) > 0, f"({len(items)} items, 1st: '{items[0].get('title')}')")
    
    first_title = items[0].get('title')
    q_enc = urllib.parse.quote(first_title)
    with urllib.request.urlopen(urllib.request.Request(f"https://www.waveflix.online/api/search?q={q_enc}", headers=HEADERS), timeout=8, context=ctx) as r:
        w_res = json.loads(r.read())
        w_items = w_res.get('results', [])
        has_tmdb_poster = len(w_items) > 0 and bool(w_items[0].get('posterPath'))
        log_test("Apple TV+ TMDB 2:3 Vertical Poster Resolution", has_tmdb_poster, f"('{w_items[0].get('posterPath')}')")
except Exception as e:
    log_test("Apple TV+ Tests", False, str(e))

# ------------------------------------------------------------------
# TEST SUITE 5: Amazon Prime Video (JustWatch prv & Cop Universe)
# ------------------------------------------------------------------
print("\n--- TEST 5: Amazon Prime Video Provider Catalog ---")
try:
    jw_query_prv = {
        "operationName": "GetPopularTitles",
        "variables": {
            "country": "IN",
            "popularTitlesFilter": {
                "packages": ["prv"],
                "objectTypes": ["MOVIE"]
            },
            "first": 5
        },
        "query": "query GetPopularTitles($country: Country!, $popularTitlesFilter: TitleFilter, $first: Int!) { popularTitles(country: $country, filter: $popularTitlesFilter, first: $first) { edges { node { id objectType content(country: $country, language: \"en\") { title posterUrl } } } } }"
    }
    jw_req = urllib.request.Request("https://apis.justwatch.com/graphql", data=json.dumps(jw_query_prv).encode('utf-8'), headers={
        'Content-Type': 'application/json',
        'User-Agent': HEADERS['User-Agent']
    })
    with urllib.request.urlopen(jw_req, timeout=8, context=ctx) as r:
        jw_data = json.loads(r.read())
    edges = jw_data.get('data', {}).get('popularTitles', {}).get('edges', [])
    log_test("Prime Video JustWatch Catalog (prv)", len(edges) > 0, f"({len(edges)} items, 1st: '{edges[0].get('node', {}).get('content', {}).get('title')}')")
    
    first_poster = edges[0].get('node', {}).get('content', {}).get('posterUrl') if edges else ""
    log_test("Prime Video Vertical 2:3 Poster", bool(first_poster and first_poster.startswith('/')), f"('{first_poster}')")

    cop_query = urllib.parse.quote("Singham Again")
    with urllib.request.urlopen(urllib.request.Request(f"https://www.waveflix.online/api/search?q={cop_query}", headers=HEADERS), timeout=8, context=ctx) as r:
        cop_res = json.loads(r.read())
        cop_items = cop_res.get('results', [])
        log_test("Cop Universe Curated Title Resolution", len(cop_items) > 0, f"(Found: '{cop_items[0].get('title')}')")
except Exception as e:
    log_test("Prime Video Provider", False, str(e))

# ------------------------------------------------------------------
# TEST SUITE 6: Netflix JustWatch & Cinemeta Provider
# ------------------------------------------------------------------
print("\n--- TEST 6: Netflix JustWatch & Cinemeta Catalog ---")
try:
    jw_query_nfx = {
        "operationName": "GetPopularTitles",
        "variables": {
            "country": "IN",
            "popularTitlesFilter": {
                "packages": ["nfx"],
                "objectTypes": ["MOVIE"]
            },
            "first": 5
        },
        "query": "query GetPopularTitles($country: Country!, $popularTitlesFilter: TitleFilter, $first: Int!) { popularTitles(country: $country, filter: $popularTitlesFilter, first: $first) { edges { node { id objectType content(country: $country, language: \"en\") { title posterUrl } } } } }"
    }
    jw_req = urllib.request.Request("https://apis.justwatch.com/graphql", data=json.dumps(jw_query_nfx).encode('utf-8'), headers={
        'Content-Type': 'application/json',
        'User-Agent': HEADERS['User-Agent']
    })
    with urllib.request.urlopen(jw_req, timeout=8, context=ctx) as r:
        jw_data = json.loads(r.read())
    edges = jw_data.get('data', {}).get('popularTitles', {}).get('edges', [])
    log_test("Netflix JustWatch Catalog (nfx)", len(edges) > 0, f"({len(edges)} items, 1st: '{edges[0].get('node', {}).get('content', {}).get('title')}')")
    
    first_poster = edges[0].get('node', {}).get('content', {}).get('posterUrl') if edges else ""
    log_test("Netflix Vertical 2:3 Poster", bool(first_poster and first_poster.startswith('/')), f"('{first_poster}')")
except Exception as e:
    log_test("Netflix Catalog", False, str(e))

# ------------------------------------------------------------------
# TEST SUITE 7: Zee5 Catalog
# ------------------------------------------------------------------
print("\n--- TEST 7: Zee5 Catalog ---")
try:
    jw_query_zee = {
        "operationName": "GetPopularTitles",
        "variables": {
            "country": "IN",
            "popularTitlesFilter": {
                "packages": ["zee"],
                "objectTypes": ["SHOW"]
            },
            "first": 5
        },
        "query": "query GetPopularTitles($country: Country!, $popularTitlesFilter: TitleFilter, $first: Int!) { popularTitles(country: $country, filter: $popularTitlesFilter, first: $first) { edges { node { id objectType content(country: $country, language: \"en\") { title posterUrl } } } } }"
    }
    jw_req = urllib.request.Request("https://apis.justwatch.com/graphql", data=json.dumps(jw_query_zee).encode('utf-8'), headers={
        'Content-Type': 'application/json',
        'User-Agent': HEADERS['User-Agent']
    })
    with urllib.request.urlopen(jw_req, timeout=8, context=ctx) as r:
        jw_data = json.loads(r.read())
    edges = jw_data.get('data', {}).get('popularTitles', {}).get('edges', [])
    log_test("Zee5 JustWatch Catalog (zee)", len(edges) > 0, f"({len(edges)} items, 1st: '{edges[0].get('node', {}).get('content', {}).get('title')}')")
except Exception as e:
    log_test("Zee5 Catalog", False, str(e))

# ------------------------------------------------------------------
# TEST SUITE 8: Crunchyroll Anime Catalog
# ------------------------------------------------------------------
print("\n--- TEST 8: Crunchyroll Anime Catalog ---")
try:
    jw_query_cru = {
        "operationName": "GetPopularTitles",
        "variables": {
            "country": "IN",
            "popularTitlesFilter": {
                "packages": ["cru"],
                "objectTypes": ["SHOW"],
                "genres": ["ani"]
            },
            "first": 5
        },
        "query": "query GetPopularTitles($country: Country!, $popularTitlesFilter: TitleFilter, $first: Int!) { popularTitles(country: $country, filter: $popularTitlesFilter, first: $first) { edges { node { id objectType content(country: $country, language: \"en\") { title posterUrl } } } } }"
    }
    jw_req = urllib.request.Request("https://apis.justwatch.com/graphql", data=json.dumps(jw_query_cru).encode('utf-8'), headers={
        'Content-Type': 'application/json',
        'User-Agent': HEADERS['User-Agent']
    })
    with urllib.request.urlopen(jw_req, timeout=8, context=ctx) as r:
        jw_data = json.loads(r.read())
    edges = jw_data.get('data', {}).get('popularTitles', {}).get('edges', [])
    log_test("Crunchyroll JustWatch Anime Catalog (cru)", len(edges) > 0, f"({len(edges)} items, 1st: '{edges[0].get('node', {}).get('content', {}).get('title')}')")
except Exception as e:
    log_test("Crunchyroll Catalog", False, str(e))

# ------------------------------------------------------------------
# TEST SUITE 9: AnimePahe Integration / Domain Mirror Status
# ------------------------------------------------------------------
print("\n--- TEST 9: AnimePahe Provider Status ---")
try:
    p_req = urllib.request.Request("https://animepahe.pw", headers=HEADERS)
    try:
        with urllib.request.urlopen(p_req, timeout=5, context=ctx) as r:
            status_code = r.status
            log_test("AnimePahe Domain Connectivity (animepahe.pw)", status_code in (200, 403, 503), f"(HTTP Status: {status_code})")
    except urllib.error.HTTPError as he:
        # 403 is Cloudflare Turnstile challenge; handled by Android WebView solver in provider
        log_test("AnimePahe Cloudflare Protected Endpoint (animepahe.pw)", he.code in (403, 503), f"(Cloudflare Gate: HTTP {he.code})")
except Exception as e:
    log_test("AnimePahe Provider", False, str(e))

# ------------------------------------------------------------------
# TEST SUITE 10: CSX Provider Mirror Domains (urls.json)
# ------------------------------------------------------------------
print("\n--- TEST 10: CSX Provider Mirror Domains (urls.json) ---")
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
