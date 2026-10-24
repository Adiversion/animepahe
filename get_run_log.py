import urllib.request
import json
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

run_id = 40074778634
jobs_url = f"https://api.github.com/repos/Adiversion/csext/actions/runs/{run_id}/jobs"
req = urllib.request.Request(jobs_url, headers={'User-Agent': 'Mozilla/5.0'})
with urllib.request.urlopen(req) as resp:
    data = json.loads(resp.read().decode('utf-8'))
    job_id = data['jobs'][0]['id']
    print(f"Job ID: {job_id}")

log_url = f"https://api.github.com/repos/Adiversion/csext/actions/jobs/{job_id}/logs"
req_log = urllib.request.Request(log_url, headers={'User-Agent': 'Mozilla/5.0'})
try:
    with urllib.request.urlopen(req_log) as lresp:
        log_text = lresp.read().decode('utf-8', errors='replace')
        lines = log_text.splitlines()
        print(f"Total log lines: {len(lines)}")
        error_lines = [l for l in lines if 'error:' in l.lower() or 'failure:' in l.lower() or 'exception' in l.lower() or 'e:' in l]
        print("=== Error lines sample ===")
        for el in error_lines[:25]:
            print(el)
        print("=== Last 40 lines ===")
        for l in lines[-40:]:
            print(l)
except Exception as e:
    print("Log fetch error:", e)
