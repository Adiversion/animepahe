import urllib.request
import time
import json
import ssl
import sys
from concurrent.futures import ThreadPoolExecutor

sys.stdout.reconfigure(encoding='utf-8')
ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

def test_apple_shelf_full_turnaround():
    start = time.time()
    url = "https://uts-api.itunes.apple.com/uts/v3/shelves/uts.col.ChartsMovies.tvs.sbd.4000?caller=web&sf=143467&v=72&pfm=web&locale=en-IN&utsk=6e3013c6d6fae3c2::::::"
    req = urllib.request.Request(url, headers={
        'User-Agent': 'Mozilla/5.0',
        'Accept': 'application/json',
        'Origin': 'https://tv.apple.com',
        'Referer': 'https://tv.apple.com/'
    })
    with urllib.request.urlopen(req, timeout=10, context=ctx) as r:
        data = json.loads(r.read())
    
    api_time = int((time.time() - start) * 1000)
    items = data.get('data', {}).get('shelf', {}).get('items', [])[:10]
    print(f"Apple TV API fetch: {api_time}ms for {len(items)} items")

    def resolve_title(item):
        t = item.get('title', '')
        t_enc = urllib.request.quote(t)
        s_url = f"https://www.waveflix.online/api/search?q={t_enc}"
        s_req = urllib.request.Request(s_url, headers={'User-Agent': 'Mozilla/5.0'})
        try:
            with urllib.request.urlopen(s_req, timeout=5, context=ctx) as sr:
                res = json.loads(sr.read())
                first_id = res.get('results', [{}])[0].get('id') if res.get('results') else None
                return t, first_id
        except:
            return t, None

    res_start = time.time()
    with ThreadPoolExecutor(max_workers=10) as ex:
        results = list(ex.map(resolve_title, items))
    
    res_time = int((time.time() - res_start) * 1000)
    total_time = int((time.time() - start) * 1000)
    print(f"Concurrent 10-item TMDB resolution: {res_time}ms")
    print(f"TOTAL Shelf Render Time: {total_time}ms (~{total_time/1000:.2f}s)")
    for t, tid in results[:3]:
        print(f"  -> '{t}' mapped to TMDB #{tid}")

if __name__ == '__main__':
    test_apple_shelf_full_turnaround()
