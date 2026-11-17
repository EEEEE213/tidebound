"""Build Tidebound .mrpack from resolved.json + the Tidebound jar. Usage: build.py resolved.json tidebound.jar out.mrpack cache_dir"""
import json, sys, os, hashlib, zipfile, urllib.request
resolved, tb_jar, out, cache = sys.argv[1:5]
version = sys.argv[5] if len(sys.argv) > 5 else "1.0.0"
os.makedirs(cache, exist_ok=True)
files = []
for m in json.load(open(resolved)):
    v = m["version"]; f = next((f for f in v["files"] if f["primary"]), v["files"][0])
    path = os.path.join(cache, f["filename"])
    if not os.path.exists(path) or hashlib.sha512(open(path, "rb").read()).hexdigest() != f["hashes"]["sha512"]:
        urllib.request.urlretrieve(f["url"], path)
    data = open(path, "rb").read()
    assert hashlib.sha512(data).hexdigest() == f["hashes"]["sha512"], f["filename"]
    side = lambda s: "unsupported" if s == "unsupported" else ("required" if s == "required" else "optional")
    files.append({"path": "mods/" + f["filename"],
                  "hashes": {"sha1": f["hashes"]["sha1"], "sha512": f["hashes"]["sha512"]},
                  "env": {"client": side(m["client"]) if m["client"] != "optional" else "required", "server": side(m["server"])},
                  "downloads": [f["url"]], "fileSize": len(data)})
index = {"formatVersion": 1, "game": "minecraft", "versionId": version, "name": "Tidebound",
         "summary": "An endless ocean of far-flung guarded islands. Sail and grow a physics ship (Sable, Create Aeronautics).",
         "files": files, "dependencies": {"minecraft": "1.21.1", "neoforge": "21.1.266"}}
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("modrinth.index.json", json.dumps(index, indent=1))
    z.write(tb_jar, "overrides/mods/" + os.path.basename(tb_jar))
    for root, _, fs in os.walk("overrides"):
        for fn in fs:
            p = os.path.join(root, fn); z.write(p, p.replace(os.sep, "/"))
print(f"{out}: {len(files)} Modrinth mods + Tidebound, {os.path.getsize(out)//1024} KB")
