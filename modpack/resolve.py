"""Resolve the Tidebound modpack against Modrinth (NeoForge 1.21.1) and write modpack/resolved.json."""
import json, sys, urllib.request, urllib.parse
UA = {"User-Agent": "modpack-builder/1.0"}
LOADERS = '["neoforge"]'; GV = '["1.21.1"]'
def get(url):
    return json.load(urllib.request.urlopen(urllib.request.Request(url, headers=UA)))
def latest(pid):
    q = urllib.parse.urlencode({"loaders": LOADERS, "game_versions": GV})
    vs = get(f"https://api.modrinth.com/v2/project/{pid}/version?{q}")
    rel = [v for v in vs if v["version_type"] == "release"] or vs
    return rel[0] if rel else None
wanted = [l.split("#")[0].strip() for l in open(sys.argv[1]) if l.split("#")[0].strip()]
done, order, incompat = {}, [], []
queue = list(wanted)
while queue:
    pid = queue.pop(0)
    proj = get(f"https://api.modrinth.com/v2/project/{pid}")
    if proj["id"] in done: continue
    v = latest(proj["id"])
    if not v: sys.exit(f"no neoforge 1.21.1 version: {pid}")
    done[proj["id"]] = dict(slug=proj["slug"], title=proj["title"], client=proj["client_side"], server=proj["server_side"], version=v)
    order.append(proj["id"])
    for d in v["dependencies"]:
        dp = d["project_id"]
        if not dp and d["version_id"]: dp = get(f"https://api.modrinth.com/v2/version/{d['version_id']}")["project_id"]
        if d["dependency_type"] == "required" and dp: queue.append(dp)
        if d["dependency_type"] == "incompatible" and dp: incompat.append((proj["slug"], dp))
for a, b in incompat:
    if b in done: print(f"!! INCOMPATIBLE: {a} vs {done[b]['slug']}")
for pid in order:
    m = done[pid]; print(f"{m['slug']:40} {m['version']['version_number']:35} c={m['client']} s={m['server']}")
json.dump([done[p] for p in order], open(sys.argv[2], "w"), indent=1)
