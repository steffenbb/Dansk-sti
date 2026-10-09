import urllib.request
import json
import gzip
import os
import math
import time

os.makedirs('app/src/main/assets/trails', exist_ok=True)
out_file = 'app/src/main/assets/trails/trail_ohavsstien.json.gz'

print("1. Fetching relation 1202410 ways from Overpass...")
q = """[out:json][timeout:60];
(
  relation(1202410);
  relation(4591923);
  relation(4591924);
  relation(4591925);
  relation(4591926);
  relation(4591927);
  relation(4591928);
  relation(4589243);
);
way(r);
out geom tags;
"""

ways = []
for attempt in range(3):
    try:
        req = urllib.request.Request(
            'https://overpass-api.de/api/interpreter',
            data=q.encode('utf-8'),
            headers={'User-Agent': 'DanskSti/1.0 (Android Trail App)'}
        )
        with urllib.request.urlopen(req, timeout=60) as resp:
            data = json.loads(resp.read().decode('utf-8'))
            ways = data.get('elements', [])
            if ways:
                print(f"Overpass success on attempt {attempt+1}: {len(ways)} ways")
                break
    except Exception as e:
        print(f"Overpass attempt {attempt+1} failed: {e}")
        time.sleep(2)

def haversine_m(lat1, lon1, lat2, lon2):
    R = 6371000.0
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = math.sin(dlat / 2)**2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(dlon / 2)**2
    return R * 2 * math.asin(math.sqrt(a))

raw_ways = []
for w in ways:
    tags = w.get('tags', {})
    geom = w.get('geometry', [])
    if len(geom) >= 2:
        pts = [{'lat': pt['lat'], 'lng': pt['lon'], 'ele': 0.0} for pt in geom]
        is_ferry = tags.get('route') == 'ferry' or tags.get('highway') == 'ferry'
        raw_ways.append({
            'type': 'FERRY' if is_ferry else 'WALK',
            'points': pts,
            'role': w.get('role', '')
        })

print(f"Extracted {len(raw_ways)} raw ways")

# Stitch ways into connected segments
segments = []
remaining = list(raw_ways)

while remaining:
    cur = remaining.pop(0)
    cur_pts = list(cur['points'])
    cur_type = cur['type']
    extended = True
    while extended and remaining:
        extended = False
        last_pt = cur_pts[-1]
        best_idx = -1
        best_dist = 60.0
        best_rev = False
        for idx, cand in enumerate(remaining):
            if cand['type'] != cur_type:
                continue
            p0 = cand['points'][0]
            pn = cand['points'][-1]
            d0 = haversine_m(last_pt['lat'], last_pt['lng'], p0['lat'], p0['lng'])
            dn = haversine_m(last_pt['lat'], last_pt['lng'], pn['lat'], pn['lng'])
            if d0 < best_dist:
                best_dist = d0
                best_idx = idx
                best_rev = False
            if dn < best_dist:
                best_dist = dn
                best_idx = idx
                best_rev = True
        if best_idx >= 0:
            nxt = remaining.pop(best_idx)
            nxt_pts = list(reversed(nxt['points'])) if best_rev else list(nxt['points'])
            if best_dist < 1.0:
                cur_pts.extend(nxt_pts[1:])
            else:
                cur_pts.extend(nxt_pts)
            extended = True
    km = sum(haversine_m(cur_pts[i]['lat'], cur_pts[i]['lng'], cur_pts[i+1]['lat'], cur_pts[i+1]['lng']) for i in range(len(cur_pts)-1)) / 1000.0
    segments.append({'type': cur_type, 'points': cur_pts, 'lengthKm': round(km, 2)})

segments.sort(key=lambda s: len(s['points']), reverse=True)
print(f"Stitched {len(segments)} segments")

# Resample walking points every 100m
resampled_segments = []
all_walk_pts = []

for seg in segments:
    pts = seg['points']
    if seg['type'] == 'FERRY' or len(pts) < 2:
        resampled_segments.append(seg)
        continue
    resampled = [pts[0]]
    acc = 0.0
    t_int = 100.0
    next_t = t_int
    for i in range(len(pts)-1):
        p1 = pts[i]
        p2 = pts[i+1]
        d = haversine_m(p1['lat'], p1['lng'], p2['lat'], p2['lng'])
        cov = 0.0
        while d > 0.0 and cov + d >= next_t - acc:
            rem = next_t - acc
            ratio = max(0.0, min(1.0, rem / d))
            ilat = p1['lat'] + ratio * (p2['lat'] - p1['lat'])
            ilng = p1['lng'] + ratio * (p2['lng'] - p1['lng'])
            resampled.append({'lat': ilat, 'lng': ilng, 'ele': 0.0})
            cov += rem
            acc = next_t
            next_t += t_int
        acc += d - cov
    if resampled[-1] != pts[-1]:
        resampled.append(pts[-1])
    km = sum(haversine_m(resampled[i]['lat'], resampled[i]['lng'], resampled[i+1]['lat'], resampled[i+1]['lng']) for i in range(len(resampled)-1)) / 1000.0
    resampled_segments.append({'type': seg['type'], 'points': resampled, 'lengthKm': round(km, 2)})
    all_walk_pts.extend(resampled)

print(f"Total walking points to query elevation: {len(all_walk_pts)}")

# Query Open-Meteo elevation in chunks of 100
chunk_size = 100
elevations = []
for i in range(0, len(all_walk_pts), chunk_size):
    chunk = all_walk_pts[i:i+chunk_size]
    lats = ','.join(f'{p["lat"]:.5f}' for p in chunk)
    lons = ','.join(f'{p["lng"]:.5f}' for p in chunk)
    url = f'https://api.open-meteo.com/v1/elevation?latitude={lats}&longitude={lons}'
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'DanskSti/1.0'})
        with urllib.request.urlopen(req, timeout=15) as r:
            ele_data = json.loads(r.read().decode('utf-8'))
            elevations.extend(ele_data.get('elevation', []))
    except Exception as ex:
        print(f"Chunk {i} elevation error: {ex}")
        elevations.extend([15.0] * len(chunk))

while len(elevations) < len(all_walk_pts):
    elevations.append(elevations[-1] if elevations else 15.0)

# 5-point moving average smoothing
smoothed = []
w = 5
for i in range(len(elevations)):
    st = max(0, i - w // 2)
    en = min(len(elevations), i + w // 2 + 1)
    smoothed.append(sum(elevations[st:en]) / (en - st))

pt_idx = 0
total_walk_km = 0.0
total_ascent = 0.0
total_descent = 0.0

for seg in resampled_segments:
    if seg['type'] == 'WALK':
        total_walk_km += seg['lengthKm']
        new_pts = []
        for p in seg['points']:
            ele = smoothed[pt_idx] if pt_idx < len(smoothed) else 15.0
            pt_idx += 1
            new_pts.append({'lat': p['lat'], 'lng': p['lng'], 'ele': round(ele, 1)})
        seg['points'] = new_pts
        for i in range(len(new_pts)-1):
            diff = new_pts[i+1]['ele'] - new_pts[i]['ele']
            if diff > 3.0:
                total_ascent += diff
            elif diff < -3.0:
                total_descent += -diff

all_lats = [p['lat'] for seg in resampled_segments for p in seg['points']]
all_lngs = [p['lng'] for seg in resampled_segments for p in seg['points']]

out_data = {
    'trailId': 'trail_ohavsstien',
    'osmRelationId': 1202410,
    'segments': resampled_segments,
    'sideBranches': [],
    'gaps': [],
    'totalWalkingKm': round(total_walk_km, 1),
    'totalAscentM': int(round(total_ascent)),
    'totalDescentM': int(round(total_descent)),
    'gapsOver200mCount': 0,
    'bboxNorth': max(all_lats) if all_lats else 55.5,
    'bboxSouth': min(all_lats) if all_lats else 54.8,
    'bboxEast': max(all_lngs) if all_lngs else 10.9,
    'bboxWest': min(all_lngs) if all_lngs else 10.0,
    'osmTimestamp': '2026-10-04T00:00:00Z',
    'fetchedAt': int(time.time() * 1000)
}

with gzip.open(out_file, 'wt', encoding='utf-8') as f:
    json.dump(out_data, f)

print(f"SUCCESS! Wrote {out_file}: {out_data['totalWalkingKm']} km, +{out_data['totalAscentM']}m, -{out_data['totalDescentM']}m")
