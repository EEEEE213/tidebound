"""Generates Tidebound's textures, models, blockstates, tags, recipes and lang.

usage: python tools/gen_assets.py <project dir>
"""
import json
import os
import random
import sys

from PIL import Image

proj = sys.argv[1]
res = os.path.join(proj, "src/main/resources")
A = os.path.join(res, "assets/tidebound")
D = os.path.join(res, "data/tidebound")


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=2, ensure_ascii=False)


def png(name, pixels):
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            img.putpixel((x, y), pixels(x, y))
    p = os.path.join(A, "textures", name + ".png")
    os.makedirs(os.path.dirname(p), exist_ok=True)
    img.save(p)


rnd = random.Random(7)


def jitter(c, amount=10):
    d = rnd.randint(-amount, amount)
    return tuple(max(0, min(255, v + d)) for v in c[:3]) + (255,)


PLANK = (110, 78, 46)
DARK = (62, 42, 26)
BRASS = (196, 156, 72)
GLOW = (80, 220, 240)


def core_side(x, y):
    # dark wood frame, brass bands, a glowing tide crystal in the middle
    if x in (0, 15) or y in (0, 15):
        return jitter(DARK, 6)
    if y in (2, 13):
        return jitter(BRASS, 12)
    cx, cy = 7.5, 7.5
    d = abs(x - cx) + abs(y - cy)
    if d <= 2.5:
        return jitter(GLOW, 8) if d > 1 else (200, 255, 255, 255)
    if d <= 3.5:
        return jitter((30, 120, 140), 8)
    return jitter(PLANK if (y // 4) % 2 == 0 else (98, 70, 40), 9)


def core_top(x, y):
    # a ship's wheel seen from above
    cx, cy = 7.5, 7.5
    r = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
    if x in (0, 15) or y in (0, 15):
        return jitter(DARK, 6)
    if r < 1.6:
        return jitter(BRASS, 10)
    if 4.6 < r < 6.2:
        return jitter((120, 84, 50), 8)
    if abs(x - cx) < 0.9 or abs(y - cy) < 0.9 or abs(abs(x - cx) - abs(y - cy)) < 0.8:
        if r < 7.2:
            return jitter((140, 100, 60), 8)
    return jitter((48, 34, 22), 5)


def core_front(x, y):
    base = core_side(x, y)
    # a brass arrow pointing up marks the bow
    if 4 <= y <= 6 and abs(x - 7.5) <= (6 - y) + 0.6:
        return jitter((240, 200, 90), 6)
    return base


def shard(x, y):
    cx, cy = 7.5, 8.0
    dx, dy = abs(x - cx), (y - cy)
    inside = dx * 1.7 + abs(dy) * 0.95 < 7.2 and dx < 4.2
    if not inside:
        return (0, 0, 0, 0)
    edge = dx * 1.7 + abs(dy) * 0.95 > 5.8 or dx > 3.4
    if edge:
        return (20, 90, 120, 255)
    t = (y / 15.0)
    c = (int(120 - 60 * t), int(240 - 80 * t), 255)
    if x - y > 1 and x - y < 4:
        c = (230, 255, 255)
    return jitter(c, 6)


png("block/ship_core_side", core_side)
png("block/ship_core_front", core_front)
png("block/ship_core_top", core_top)
png("block/ship_core_bottom", lambda x, y: jitter(DARK, 6))
png("item/tide_shard", shard)

write(os.path.join(A, "blockstates/ship_core.json"), {
    "variants": {
        "facing=north": {"model": "tidebound:block/ship_core"},
        "facing=east": {"model": "tidebound:block/ship_core", "y": 90},
        "facing=south": {"model": "tidebound:block/ship_core", "y": 180},
        "facing=west": {"model": "tidebound:block/ship_core", "y": 270},
    }
})
write(os.path.join(A, "models/block/ship_core.json"), {
    "parent": "minecraft:block/block",
    "textures": {
        "particle": "tidebound:block/ship_core_side",
        "side": "tidebound:block/ship_core_side",
        "front": "tidebound:block/ship_core_front",
        "top": "tidebound:block/ship_core_top",
        "bottom": "tidebound:block/ship_core_bottom",
    },
    "elements": [
        {"from": [1, 0, 1], "to": [15, 12, 15], "faces": {
            "north": {"texture": "#front", "uv": [1, 2, 15, 14]},
            "south": {"texture": "#side", "uv": [1, 2, 15, 14]},
            "east": {"texture": "#side", "uv": [1, 2, 15, 14]},
            "west": {"texture": "#side", "uv": [1, 2, 15, 14]},
            "up": {"texture": "#top", "uv": [1, 1, 15, 15]},
            "down": {"texture": "#bottom", "cullface": "down"},
        }},
        {"from": [6, 12, 6], "to": [10, 14, 10], "faces": {
            f: {"texture": "#bottom"} for f in ("north", "south", "east", "west", "up")
        }},
    ],
})
write(os.path.join(A, "models/item/ship_core.json"), {"parent": "tidebound:block/ship_core"})
write(os.path.join(A, "models/item/tide_shard.json"), {"parent": "minecraft:item/generated", "layers": [] } )
write(os.path.join(A, "models/item/tide_shard.json"), {"parent": "minecraft:item/generated", "textures": {"layer0": "tidebound:item/tide_shard"}})

write(os.path.join(D, "tags/item/tide_shards.json"), {"values": ["tidebound:tide_shard"]})
write(os.path.join(D, "loot_table/blocks/ship_core.json"), {
    "type": "minecraft:block",
    "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": "tidebound:ship_core"}],
               "conditions": [{"condition": "minecraft:survives_explosion"}]}],
})
write(os.path.join(D, "recipe/ship_core.json"), {
    "type": "minecraft:crafting_shaped",
    "category": "misc",
    "pattern": ["PSP", "LCL", "PLP"],
    "key": {"P": {"tag": "minecraft:planks"}, "L": {"tag": "minecraft:logs"},
            "S": {"item": "tidebound:tide_shard"}, "C": {"item": "minecraft:compass"}},
    "result": {"id": "tidebound:ship_core", "count": 1},
})
write(os.path.join(res, "data/minecraft/tags/block/mineable/axe.json"), {"replace": False, "values": ["tidebound:ship_core"]})

lang = {
    "itemGroup.tidebound": "Tidebound",
    "block.tidebound.ship_core": "Ship Core",
    "item.tidebound.ship_core.tip1": "Use on open water to launch a raft",
    "item.tidebound.ship_core.tip2": "Or place it in your own hull and launch it from the core",
    "item.tidebound.tide_shard": "Tide Shard",
    "screen.tidebound.ship_core": "Ship Core",
    "screen.tidebound.take_helm": "Take the Helm",
    "screen.tidebound.launch": "Launch Ship",
    "screen.tidebound.launch.tip": "Everything built onto this core (not terrain) becomes a floating physics ship",
    "screen.tidebound.install": "Install",
    "screen.tidebound.installed": "Installed",
    "screen.tidebound.upgrades": "Shipwright's Upgrades",
    "screen.tidebound.stats": "%s b/s · Rudder %s · Stability %s",
    "screen.tidebound.afloat": "Afloat - a physics ship",
    "screen.tidebound.ashore": "Not launched yet",
    "hud.tidebound.helm": "At the Helm",
    "hud.tidebound.keys": "W/S throttle  A/D rudder  Sneak: leave helm",
    "hud.tidebound.locator": "Crow's Nest lookout",
    "message.tidebound.raft_launched": "Your raft is afloat! Right-click the Ship Core to take the helm.",
    "message.tidebound.raft_blocked": "Not enough open water here for a raft",
    "message.tidebound.raft_shallow": "Too shallow here: a raft needs %s blocks of water under it. Head further out from the shore.",
    "message.tidebound.helm_left": "You were left behind and let go of the helm. Stand on the deck while you steer.",
    "screen.tidebound.preview_with": "With %s",
    "screen.tidebound.preview_have": "%s (installed)",
    "screen.tidebound.preview_hover": "Hover an upgrade to preview it",
    "message.tidebound.aground": "The ship won't budge: it's aground and needs deeper water.",
    "message.tidebound.already_ship": "This core is already part of a ship",
    "message.tidebound.too_big": "That's too big to launch (max %s blocks)",
    "message.tidebound.nothing": "Nothing to launch",
    "message.tidebound.launched": "Launched!",
    "message.tidebound.not_a_ship": "Launch the ship first",
    "message.tidebound.helm_taken": "Someone else is at the helm",
    "message.tidebound.installed": "Already installed",
    "message.tidebound.requires": "Requires %s",
    "message.tidebound.missing_materials": "Missing materials",
    "message.tidebound.upgrade_done": "%s installed (%s blocks built)",
    "message.tidebound.protected": "The %s still guards %s - defeat it to loot the island",
    "message.tidebound.guardian_awakens": "%s awakens!",
    "message.tidebound.welcome1": "Welcome to the endless sea. Use the Ship Core on open water to launch your raft.",
    "message.tidebound.welcome2": "Every island is guarded. Defeat the guardian, loot the island, and upgrade your ship at the core.",
    "title.tidebound.freed": "Island Freed!",
    "title.tidebound.freed.sub": "%s is yours to loot",
    "container.tidebound.island_cache": "%s Cache",
    "boss.tidebound.hollow_king": "Hollow King",
    "boss.tidebound.thornback": "Thornback",
    "boss.tidebound.tidecaller": "Tidecaller",
    "boss.tidebound.broodmother": "Broodmother",
    "boss.tidebound.hexweaver": "Hexweaver",
    "boss.tidebound.grave_titan": "Grave Titan",
    "upgrade.tidebound.expanded_hull": "Expanded Hull",
    "upgrade.tidebound.expanded_hull.desc": "Builds a 41-block ship's hull around your raft: tapered bow, raised forecastle, and a furnished lower deck with bunks, a mess, a galley and a cargo hold.",
    "upgrade.tidebound.gunwales": "Gunwales",
    "upgrade.tidebound.gunwales.desc": "Bulwarks and rails all round the deck, lanterns on the rail posts, boarding gaps amidships.",
    "upgrade.tidebound.mainmast": "Mainmast & Sail",
    "upgrade.tidebound.mainmast.desc": "A 26-block mainmast with a course, a topsail and a pennant.",
    "upgrade.tidebound.keel": "Keel & Rudder",
    "upgrade.tidebound.keel.desc": "Keel, stem post and rudder: much sharper turns and a steadier ship.",
    "upgrade.tidebound.cabin": "Captain's Cabin",
    "upgrade.tidebound.cabin.desc": "A captain's cabin at the stern with windows, a door and furniture, and a railed quarterdeck on its roof.",
    "upgrade.tidebound.crows_nest": "Crow's Nest",
    "upgrade.tidebound.crows_nest.desc": "A lookout platform with a ladder up the mainmast. Shows the nearest unclaimed island on your helm HUD.",
    "upgrade.tidebound.foremast": "Foremast & Jib",
    "upgrade.tidebound.foremast.desc": "A foremast with two sails, a bowsprit and a jib.",
    "upgrade.tidebound.iron_prow": "Iron Prow",
    "upgrade.tidebound.iron_prow.desc": "A ram at the waterline, a sheathed cutwater and two anchors.",
    "upgrade.tidebound.paddle_steamer": "Paddle Steamer",
    "upgrade.tidebound.paddle_steamer.desc": "Paddle wheels in arched boxes, a boiler house and twin smoking funnels.",
}
write(os.path.join(A, "lang/en_us.json"), lang)
print("ok")
