"""Generates the archipelago worldgen JSON from vanilla's overworld noise settings.

usage: python tools/gen_worldgen.py <vanilla overworld.json> <project dir>
"""
import json
import os
import sys

src, proj = sys.argv[1], sys.argv[2]
with open(src) as f:
    ns = json.load(f)

# solid where surface_height(x, z) > y.  y_clamped_gradient here is exactly -y over the whole build height.
height_minus_y = {
    "type": "minecraft:add",
    "argument1": {"type": "minecraft:cache_2d", "argument": "tidebound:surface_height"},
    "argument2": {
        "type": "minecraft:y_clamped_gradient",
        "from_y": -64, "to_y": 320, "from_value": 64.0, "to_value": -320.0,
    },
}
density = {"type": "minecraft:mul", "argument1": 0.25, "argument2": height_minus_y}

router = ns["noise_router"]
router["final_density"] = density
router["initial_density_without_jaggedness"] = density
ns["aquifers_enabled"] = False
ns["sea_level"] = 63

out = os.path.join(proj, "src/main/resources/data/tidebound/worldgen/noise_settings/archipelago.json")
os.makedirs(os.path.dirname(out), exist_ok=True)
with open(out, "w") as f:
    json.dump(ns, f, indent=2)

density_fn = os.path.join(proj, "src/main/resources/data/tidebound/worldgen/density_function/surface_height.json")
os.makedirs(os.path.dirname(density_fn), exist_ok=True)
with open(density_fn, "w") as f:
    json.dump({"type": "tidebound:surface_height"}, f, indent=2)

biome_source = {
    "type": "tidebound:archipelago",
    "island_biomes": [
        "minecraft:forest", "minecraft:birch_forest", "minecraft:plains", "minecraft:taiga",
        "minecraft:jungle", "minecraft:savanna", "minecraft:cherry_grove", "minecraft:dark_forest",
        "minecraft:flower_forest", "minecraft:snowy_taiga", "minecraft:meadow", "minecraft:old_growth_pine_taiga",
        "minecraft:sparse_jungle", "minecraft:windswept_forest",
    ],
    "home_biome": "minecraft:forest",
    "beach": "minecraft:beach",
    "snowy_beach": "minecraft:snowy_beach",
    "ocean": "minecraft:ocean",
    "deep_ocean": "minecraft:deep_ocean",
    "warm_ocean": "minecraft:warm_ocean",
    "lukewarm_ocean": "minecraft:lukewarm_ocean",
    "deep_lukewarm_ocean": "minecraft:deep_lukewarm_ocean",
    "cold_ocean": "minecraft:cold_ocean",
    "deep_cold_ocean": "minecraft:deep_cold_ocean",
}

overworld = {
    "type": "minecraft:overworld",
    "generator": {"type": "minecraft:noise", "biome_source": biome_source, "settings": "tidebound:archipelago"},
}

preset = {
    "dimensions": {
        "minecraft:overworld": overworld,
        "minecraft:the_end": {
            "type": "minecraft:the_end",
            "generator": {"type": "minecraft:noise", "biome_source": {"type": "minecraft:the_end"}, "settings": "minecraft:end"},
        },
        "minecraft:the_nether": {
            "type": "minecraft:the_nether",
            "generator": {
                "type": "minecraft:noise",
                "biome_source": {"type": "minecraft:multi_noise", "preset": "minecraft:nether"},
                "settings": "minecraft:nether",
            },
        },
    }
}

# Our own preset, plus an override of vanilla's "Default" so every new world is the archipelago.
for path in ("data/tidebound/worldgen/world_preset/archipelago.json",
             "data/minecraft/worldgen/world_preset/normal.json"):
    p = os.path.join(proj, "src/main/resources", path)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w") as f:
        json.dump(preset, f, indent=2)

print("ok")
