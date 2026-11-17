"""
Renders the Ship Core presets in Blender (Cycles) from the exact block layouts the mod builds.

  blender -b --factory-startup --python tools/render/render_presets.py -- <blueprints.json> <out_dir> [--quick]

blueprints.json comes from the exportBlueprints game test (run/blueprints.json). Block shapes and textures are read from
the real game files: vanilla assets extracted to tools/render/mc, Tidebound's own from src/main/resources.
Per stage it writes <stage>.png (transparent, orthographic, soft shadow), plus hero.png (full ship on the sea).
"""
import bpy, bmesh, json, math, os, sys
from mathutils import Vector, Matrix

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PACKS = [os.path.join(ROOT, "tools", "render", "mc", "assets"), os.path.join(ROOT, "src", "main", "resources", "assets")]

argv = sys.argv[sys.argv.index("--") + 1:]
BLUEPRINTS, OUT = os.path.abspath(argv[0]), os.path.abspath(argv[1])
QUICK = "--quick" in argv
ONLY = [s for a in argv if a.startswith("--only=") for s in a.split("=", 1)[1].split(",")]
os.makedirs(OUT, exist_ok=True)


def asset(ns_path, kind, ext):
    ns, path = ns_path.split(":", 1) if ":" in ns_path else ("minecraft", ns_path)
    for root in PACKS:
        p = os.path.join(root, ns, kind, path + ext)
        if os.path.exists(p):
            return p
    raise FileNotFoundError(f"{kind}/{ns_path}{ext}")


# ------------------------------------------------------------------ block models

_models = {}


def load_model(name):
    """Resolved model: (textures dict, elements list)."""
    if name in _models:
        return _models[name]
    with open(asset(name, "models", ".json")) as f:
        m = json.load(f)
    textures, elements = {}, None
    if "parent" in m and not m["parent"].startswith("builtin/"):
        pt, pe = load_model(m["parent"])
        textures.update(pt)
        elements = pe
    textures.update(m.get("textures", {}))
    if "elements" in m:
        elements = m["elements"]
    _models[name] = (textures, elements or [])
    return _models[name]


def resolve_tex(textures, ref, depth=0):
    while ref.startswith("#") and depth < 10:
        ref = textures.get(ref[1:], "")
        depth += 1
    return ref


def parse_state(s):
    name, props = (s.split("[", 1) + [""])[:2]
    props = dict(kv.split("=") for kv in props.rstrip("]").split(",") if kv)
    return name, props


def when_matches(cond, props):
    if "OR" in cond:
        return any(when_matches(c, props) for c in cond["OR"])
    if "AND" in cond:
        return all(when_matches(c, props) for c in cond["AND"])
    return all(props.get(k) in str(v).split("|") for k, v in cond.items())


def models_for(state):
    name, props = parse_state(state)
    with open(asset(name, "blockstates", ".json")) as f:
        bs = json.load(f)
    out = []
    if "variants" in bs:
        for key, v in bs["variants"].items():
            want = dict(kv.split("=") for kv in key.split(",") if kv)
            if all(props.get(k) == val for k, val in want.items()):
                out.append(v[0] if isinstance(v, list) else v)
                break
    for part in bs.get("multipart", []):
        if "when" not in part or when_matches(part["when"], props):
            a = part["apply"]
            out.append(a[0] if isinstance(a, list) else a)
    return out


FACES = {
    # corners TL, TR, BR, BL as seen from outside, in MC element space
    "north": lambda a, b: [(b[0], b[1], a[2]), (a[0], b[1], a[2]), (a[0], a[1], a[2]), (b[0], a[1], a[2])],
    "south": lambda a, b: [(a[0], b[1], b[2]), (b[0], b[1], b[2]), (b[0], a[1], b[2]), (a[0], a[1], b[2])],
    "west": lambda a, b: [(a[0], b[1], a[2]), (a[0], b[1], b[2]), (a[0], a[1], b[2]), (a[0], a[1], a[2])],
    "east": lambda a, b: [(b[0], b[1], b[2]), (b[0], b[1], a[2]), (b[0], a[1], a[2]), (b[0], a[1], b[2])],
    "up": lambda a, b: [(a[0], b[1], a[2]), (b[0], b[1], a[2]), (b[0], b[1], b[2]), (a[0], b[1], b[2])],
    "down": lambda a, b: [(a[0], a[1], b[2]), (b[0], a[1], b[2]), (b[0], a[1], a[2]), (a[0], a[1], a[2])],
}


def default_uv(face, a, b):
    x1, y1, z1 = a
    x2, y2, z2 = b
    return {
        "down": [x1, 16 - z2, x2, 16 - z1], "up": [x1, z1, x2, z2],
        "north": [16 - x2, 16 - y2, 16 - x1, 16 - y1], "south": [x1, 16 - y2, x2, 16 - y1],
        "west": [z1, 16 - y2, z2, 16 - y1], "east": [16 - z2, 16 - y2, 16 - z1, 16 - y1],
    }[face]


DIRS = {"north": (0, 0, -1), "south": (0, 0, 1), "west": (-1, 0, 0), "east": (1, 0, 0), "up": (0, 1, 0), "down": (0, -1, 0)}


def rot_point(p, axis, angle, origin):
    v = Vector(p) - Vector(origin)
    ax = {"x": "X", "y": "Y", "z": "Z"}[axis]
    v = Matrix.Rotation(math.radians(angle), 3, ax) @ v
    return tuple(v + Vector(origin))


def model_rot(p, xr, yr):
    x, y, z = p
    for _ in range((xr // 90) % 4):  # about X through the block centre
        y, z = 16 - z, y
    for _ in range((yr // 90) % 4):  # clockwise seen from above: north -> east
        x, z = 16 - z, x
    return (x, y, z)


def rot_dir(d, xr, yr):
    x, y, z = DIRS[d]
    for _ in range((xr // 90) % 4):
        y, z = -z, y
    for _ in range((yr // 90) % 4):
        x, z = -z, x
    return next(k for k, v in DIRS.items() if v == (x, y, z))


# Chests render as block entities; give them a box with the entity texture (normal.png, 64x64).
def chest_quads(props):
    t = "minecraft:entity/chest/normal"
    quads = []

    def box(a, b, uvs):
        for face, uv in uvs.items():
            quads.append((FACES[face](a, b), uv, t, None, face))
    s = 64 / 16  # atlas px -> 0..16 uv space
    base = {"up": [28, 19, 42, 33], "down": [14, 19, 28, 33], "north": [42, 33, 56, 43], "south": [14, 33, 28, 43],
            "west": [28, 33, 42, 43], "east": [0, 33, 14, 43]}
    lid = {"up": [28, 0, 42, 14], "down": [14, 0, 28, 14], "north": [42, 14, 56, 19], "south": [14, 14, 28, 19],
           "west": [28, 14, 42, 19], "east": [0, 14, 14, 19]}
    lock = {f: [1, 1, 3, 5] for f in DIRS}
    box((1, 0, 1), (15, 10, 15), {k: [c / s for c in v] for k, v in base.items()})
    box((1, 9, 1), (15, 14, 15), {k: [c / s for c in v] for k, v in lid.items()})
    box((7, 7, 0), (9, 11, 1), {k: [c / s for c in v] for k, v in lock.items()})
    yr = {"north": 0, "east": 90, "south": 180, "west": 270}[props.get("facing", "north")]
    # the chest model faces south when unrotated, like the vanilla entity
    return [([model_rot(p, 0, yr + 180) for p in c], uv, tex, 0, rot_dir(f, 0, yr + 180)) for c, uv, tex, _, f in quads]


OPAQUE = ("planks", "_log", "wool", "iron_block", "bricks", "crafting_table", "furnace", "barrel", "ship_core", "blast_furnace")


def is_full(state):
    name = parse_state(state)[0]
    return any(k in name for k in OPAQUE) and "slab" not in name and "stairs" not in name


def block_quads(state, cull):
    """[(corners in 0..16, uv rect, texture id, uv rotation, face dir)] after culling faces against full neighbours."""
    name, props = parse_state(state)
    if name.endswith(":chest"):
        return chest_quads(props)
    if name.endswith("_bed"):
        colour = name.split(":")[1].replace("_bed", "")
        top = "minecraft:block/white_wool" if props.get("part") == "head" else f"minecraft:block/{colour}_wool"
        out = []
        for face in DIRS:
            if face in cull:
                continue
            tex = top if face == "up" else ("minecraft:block/oak_planks" if face == "down" else f"minecraft:block/{colour}_wool")
            a, b = (0, 3, 0), (16, 9, 16)
            out.append((FACES[face](a, b), default_uv(face, a, b), tex, 0, face))
        return out
    out = []
    for v in models_for(state):
        textures, elements = load_model(v["model"])
        xr, yr = v.get("x", 0), v.get("y", 0)
        for el in elements:
            a, b = el["from"], el["to"]
            r = el.get("rotation")
            for face, fd in el.get("faces", {}).items():
                world_dir = rot_dir(face, xr, yr)
                if "cullface" in fd and rot_dir(fd["cullface"], xr, yr) in cull:
                    continue
                corners = FACES[face](a, b)
                if r:
                    corners = [rot_point(p, r["axis"], r["angle"], r["origin"]) for p in corners]
                corners = [model_rot(p, xr, yr) for p in corners]
                tex = resolve_tex(textures, fd["texture"])
                out.append((corners, fd.get("uv") or default_uv(face, a, b), tex, fd.get("rotation", 0) or 0, world_dir))
    return out


# ------------------------------------------------------------------ materials

_mats = {}


def material(tex):
    if tex in _mats:
        return _mats[tex]
    path = asset(tex, "textures", ".png")
    img = bpy.data.images.load(path, check_existing=True)
    m = bpy.data.materials.new(tex)
    m.use_nodes = True
    nt = m.node_tree
    bsdf = nt.nodes["Principled BSDF"]
    node = nt.nodes.new("ShaderNodeTexImage")
    node.image = img
    node.interpolation = "Closest"
    nt.links.new(node.outputs["Color"], bsdf.inputs["Base Color"])
    nt.links.new(node.outputs["Alpha"], bsdf.inputs["Alpha"])
    metal = "iron" in tex or "lantern" in tex
    bsdf.inputs["Roughness"].default_value = 0.45 if metal else 0.85
    bsdf.inputs["Metallic"].default_value = 0.6 if metal else 0.0
    if "glass" in tex:
        bsdf.inputs["Roughness"].default_value = 0.05
    if "lantern" in tex or "campfire_fire" in tex or "fire" in tex.split("/")[-1]:
        bsdf.inputs["Emission Color"].default_value = (1.0, 0.7, 0.35, 1)
        nt.links.new(node.outputs["Color"], bsdf.inputs["Emission Color"])
        bsdf.inputs["Emission Strength"].default_value = 2.0
    m.blend_method = "HASHED" if hasattr(m, "blend_method") else None
    _mats[tex] = (m, img.size[0], img.size[1])
    return _mats[tex]


# ------------------------------------------------------------------ scene

def build_ship(blocks, name):
    full = {(x, y, z) for x, y, z, _, s in blocks if is_full(s)}
    mesh = bpy.data.meshes.new(name)
    bm = bmesh.new()
    uv_layer = bm.loops.layers.uv.new()
    mat_index = {}
    mats = []
    for x, y, z, _, state in blocks:
        cull = set()
        for d, (dx, dy, dz) in DIRS.items():
            if (x + dx, y + dy, z + dz) in full:
                cull.add(d)
        for corners, uv, tex, uvrot, _ in block_quads(state, cull):
            if tex not in mat_index:
                mat_index[tex] = len(mats)
                mats.append(material(tex))
            m, w, h = mats[mat_index[tex]]
            vs = []
            for cx, cy, cz in corners:
                # MC (x, y, z) -> Blender (x, -z, y); one block = one unit
                vs.append(bm.verts.new((x + cx / 16, -(z + cz / 16), y + cy / 16)))
            try:
                f = bm.faces.new(vs)
            except ValueError:
                continue
            f.material_index = mat_index[tex]
            u1, v1, u2, v2 = uv
            fv = h / w if h > w else 1.0  # animated strips: use the first frame
            uvs = [(u1, v1), (u2, v1), (u2, v2), (u1, v2)]
            k = (uvrot // 90) % 4
            uvs = uvs[k:] + uvs[:k]
            for loop, (u, v) in zip(f.loops, uvs):
                loop[uv_layer].uv = (u / 16, 1 - (v / 16) / fv)
    bm.to_mesh(mesh)
    bm.free()
    for m, _, _ in mats:
        mesh.materials.append(m)
    obj = bpy.data.objects.new(name, mesh)
    bpy.context.scene.collection.objects.link(obj)
    return obj


def setup_render(w, h, transparent):
    sc = bpy.context.scene
    sc.render.engine = "CYCLES"
    prefs = bpy.context.preferences.addons["cycles"].preferences
    for dev_type in ("OPTIX", "CUDA"):
        try:
            prefs.compute_device_type = dev_type
            prefs.get_devices()
            if any(d.type == dev_type for d in prefs.devices):
                for d in prefs.devices:
                    d.use = d.type == dev_type
                sc.cycles.device = "GPU"
                break
        except Exception:
            pass
    sc.cycles.samples = 24 if QUICK else 192
    sc.cycles.use_denoising = True
    sc.render.resolution_x, sc.render.resolution_y = w, h
    sc.render.film_transparent = transparent
    sc.view_settings.view_transform = "AgX"
    sc.view_settings.look = "AgX - Medium High Contrast"
    sc.render.image_settings.file_format = "PNG"
    sc.render.image_settings.color_mode = "RGBA"


def world_sky(strength=1.0, color=None):
    world = bpy.data.worlds.new("sky")
    bpy.context.scene.world = world
    world.use_nodes = True
    nt = world.node_tree
    bg = nt.nodes["Background"]
    if color:
        bg.inputs["Color"].default_value = color
    else:
        sky = nt.nodes.new("ShaderNodeTexSky")
        sky.sky_type = "NISHITA" if hasattr(sky, "sky_type") else sky.sky_type
        sky.sun_elevation = math.radians(35)
        sky.sun_rotation = math.radians(120)
        nt.links.new(sky.outputs["Color"], bg.inputs["Color"])
    bg.inputs["Strength"].default_value = strength


def light(kind, loc, rot, energy, size=None, color=(1, 1, 1)):
    ld = bpy.data.lights.new(kind, kind)
    ld.energy = energy
    ld.color = color
    if size is not None:
        if kind == "AREA":
            ld.size = size
        elif kind == "SUN":
            ld.angle = math.radians(size)
    o = bpy.data.objects.new(kind, ld)
    o.location = loc
    o.rotation_euler = [math.radians(a) for a in rot]
    bpy.context.scene.collection.objects.link(o)
    return o


def look_at(cam, target):
    d = Vector(target) - cam.location
    cam.rotation_euler = d.to_track_quat("-Z", "Y").to_euler()


def fit_ortho(cam, cam_data, blocks, aspect, margin=1.08):
    """Same framing for every stage: fit the whole finished ship's corners in view."""
    bpy.context.view_layer.update()
    inv = cam.matrix_world.inverted()
    xs, ys = [], []
    for x, y, z, _, _ in blocks:
        for dx in (0, 1):
            for dy in (0, 1):
                for dz in (0, 1):
                    p = inv @ Vector((x + dx, -(z + dz), y + dy))
                    xs.append(p.x)
                    ys.append(p.y)
    w, h = max(xs) - min(xs), max(ys) - min(ys)
    scale = max(w, h * aspect) * margin
    cam_data.ortho_scale = scale
    cam_data.shift_x = (max(xs) + min(xs)) / 2 / scale
    cam_data.shift_y = (max(ys) + min(ys)) / 2 / scale


def clear():
    for o in list(bpy.data.objects):
        bpy.data.objects.remove(o, do_unlink=True)


def bounds(blocks):
    xs = [b[0] for b in blocks]; ys = [b[1] for b in blocks]; zs = [b[2] for b in blocks]
    return (min(xs), max(xs) + 1), (min(ys), max(ys) + 1), (min(zs), max(zs) + 1)


WATER_Z = -2.55


def main():
    with open(BLUEPRINTS) as f:
        data = json.load(f)
    stages, blocks = data["stages"], data["blocks"]
    final = [b[:5] for b in blocks if len(b) < 6]
    global WATER_Z
    WATER_Z = -9.0 if "galleon" in stages else -2.55  # design waterline (galleon: under the orlop floor)
    pre = len(stages) - 2  # the last stage before the galleon frames the earlier stages
    frame_pre = [b[:5] for b in blocks if b[3] <= pre and (len(b) < 6 or pre < b[5])]
    (x0, x1), (y0, y1), (z0, z1) = bounds(final)
    centre = Vector(((x0 + x1) / 2, -(z0 + z1) / 2, (y0 + y1) / 2))
    span = max(x1 - x0, y1 - y0, z1 - z0)

    for i, stage in enumerate(stages):
        if ONLY and stage not in ONLY and "all" not in ONLY:
            continue
        clear()
        bpy.ops.wm.read_factory_settings(use_empty=True) if False else None
        build_ship([b[:5] for b in blocks if b[3] <= i and (len(b) < 6 or i < b[5])], stage)
        # shadow catcher under the hull
        bpy.ops.mesh.primitive_plane_add(size=span * 6, location=(centre.x, centre.y, y0 - 0.01))
        bpy.context.object.is_shadow_catcher = True
        setup_render(960, 720, transparent=True)
        world_sky(0.55, (0.62, 0.72, 0.85, 1))
        light("SUN", (0, 0, 20), (40, 0, 145), 3.2, 4, (1.0, 0.96, 0.9))
        light("AREA", (centre.x - span, centre.y - span, centre.z + span), (55, 0, -135), 400 * span / 10, span)
        cam_data = bpy.data.cameras.new("cam")
        cam_data.type = "ORTHO"
        cam_data.ortho_scale = span * 1.62
        cam = bpy.data.objects.new("cam", cam_data)
        bpy.context.scene.collection.objects.link(cam)
        bpy.context.scene.camera = cam
        # three-quarter view from the starboard bow, slightly above
        cam.location = centre + Vector((span * 1.1, span * 1.25, span * 0.85))
        look_at(cam, centre)
        fit_ortho(cam, cam_data, final if stage == "galleon" else frame_pre, 960 / 720)
        bpy.context.scene.render.filepath = os.path.join(OUT, f"{i:02d}_{stage}.png")
        bpy.ops.render.render(write_still=True)
        print("rendered", stage, flush=True)

    if not ONLY or "lower_deck" in ONLY or "all" in ONLY:
        # cutaway: the finished ship with everything from the main deck up lifted off
        clear()
        cut = [b for b in final if b[1] <= -2]
        build_ship(cut, "lower_deck")
        bpy.ops.mesh.primitive_plane_add(size=span * 6, location=(centre.x, centre.y, y0 - 0.01))
        bpy.context.object.is_shadow_catcher = True
        setup_render(1600, 900, transparent=True)
        world_sky(0.6, (0.62, 0.72, 0.85, 1))
        light("SUN", (0, 0, 20), (30, 0, 150), 3.0, 4, (1.0, 0.96, 0.9))
        cam_data = bpy.data.cameras.new("cam")
        cam_data.type = "ORTHO"
        cam = bpy.data.objects.new("cam", cam_data)
        bpy.context.scene.collection.objects.link(cam)
        bpy.context.scene.camera = cam
        cam.location = centre + Vector((span * 0.55, span * 0.25, span * 1.2))
        look_at(cam, centre)
        fit_ortho(cam, cam_data, cut, 1600 / 900, 1.04)
        bpy.context.scene.render.filepath = os.path.join(OUT, "lower_deck.png")
        bpy.ops.render.render(write_still=True)
        print("rendered lower_deck", flush=True)

    if not ONLY or "section" in ONLY or "all" in ONLY:
        # side cutaway: the port half of the finished ship seen from starboard, showing every deck
        clear()
        cut = [b for b in final if b[0] <= 0]
        build_ship(cut, "section")
        bpy.ops.mesh.primitive_plane_add(size=span * 8, location=(centre.x, centre.y, y0 - 0.01))
        bpy.context.object.is_shadow_catcher = True
        setup_render(1920, 1080, transparent=True)
        world_sky(0.7, (0.62, 0.72, 0.85, 1))
        light("SUN", (0, 0, 20), (60, 0, 70), 2.6, 4, (1.0, 0.96, 0.9))
        light("AREA", (centre.x + span, centre.y, centre.z + 10), (0, 80, 0), 1500, span)
        cam_data = bpy.data.cameras.new("cam")
        cam_data.type = "ORTHO"
        cam = bpy.data.objects.new("cam", cam_data)
        bpy.context.scene.collection.objects.link(cam)
        bpy.context.scene.camera = cam
        cam.location = centre + Vector((span * 1.5, -span * 0.25, span * 0.12))
        look_at(cam, centre)
        fit_ortho(cam, cam_data, cut, 1920 / 1080, 1.03)
        bpy.context.scene.render.filepath = os.path.join(OUT, "section.png")
        bpy.ops.render.render(write_still=True)
        print("rendered section", flush=True)

    if not ONLY or "hero" in ONLY or "all" in ONLY:
        clear()
        build_ship([b[:5] for b in blocks if len(b) < 6], "hero")
        bpy.ops.mesh.primitive_plane_add(size=6000, location=(centre.x, centre.y, WATER_Z))
        water = bpy.context.object
        wm = bpy.data.materials.new("water")
        wm.use_nodes = True
        b = wm.node_tree.nodes["Principled BSDF"]
        b.inputs["Base Color"].default_value = (0.01, 0.06, 0.12, 1)
        b.inputs["Roughness"].default_value = 0.16
        b.inputs["IOR"].default_value = 1.33
        b.inputs["Transmission Weight"].default_value = 0.35
        noise = wm.node_tree.nodes.new("ShaderNodeTexNoise")
        noise.inputs["Scale"].default_value = 0.6
        noise.inputs["Detail"].default_value = 8
        bump = wm.node_tree.nodes.new("ShaderNodeBump")
        bump.inputs["Strength"].default_value = 0.5
        wm.node_tree.links.new(noise.outputs["Fac"], bump.inputs["Height"])
        wm.node_tree.links.new(bump.outputs["Normal"], b.inputs["Normal"])
        water.data.materials.append(wm)
        setup_render(1600, 900, transparent=False)
        world_sky(0.3)
        bpy.context.scene.view_settings.exposure = -1.3
        light("SUN", (0, 0, 20), (50, 0, 125), 2.2, 1.5, (1.0, 0.9, 0.78))
        cam_data = bpy.data.cameras.new("cam")
        cam_data.lens = 40
        cam = bpy.data.objects.new("cam", cam_data)
        bpy.context.scene.collection.objects.link(cam)
        bpy.context.scene.camera = cam
        cam.location = centre + Vector((span * 1.5, span * 1.25, span * 0.42))
        look_at(cam, centre + Vector((0, -0.5, -0.6)))
        bpy.context.scene.render.filepath = os.path.join(OUT, "hero.png")
        bpy.ops.render.render(write_still=True)
        print("rendered hero", flush=True)


main()
