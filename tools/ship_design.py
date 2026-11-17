"""
Tidebound ship designer: generates the starter raft and the nine premade upgrades of the Ship Core.

  python tools/ship_design.py            -> src/main/resources/data/tidebound/ship_blueprints/<stage>.json
                                           + run/design_preview.json (cumulative, for tools/render/render_presets.py)

Coordinates are "deck coordinates" for a ship facing north: r = right (east), y = up (main deck = 0), f = forward.
The Ship Core sits at (0, 1, -2). Block states are written for that orientation; the mod rotates them with the ship.
Later stages may replace blocks of earlier stages (the mod only does that while those blocks are untouched).
"""
import json, math, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "src", "main", "resources", "data", "tidebound", "ship_blueprints")
CORE = (0, 1, -2)

# ---------------------------------------------------------------- palette
HULL = "minecraft:dark_oak_planks"
HULL_LOW = "minecraft:spruce_planks"
WALE = "minecraft:stripped_spruce_log[axis=z]"
DECK = "minecraft:oak_planks"
DECK2 = "minecraft:spruce_planks"
TRIM = "minecraft:stripped_spruce_log"
MAST = "minecraft:dark_oak_log[axis=y]"
YARD = "minecraft:spruce_log[axis=x]"
SAIL = "minecraft:white_wool"
RAIL = "minecraft:spruce_fence"
LANTERN = "minecraft:lantern[hanging=false]"

STAGES = ["raft", "expanded_hull", "gunwales", "mainmast", "keel", "cabin", "crows_nest", "foremast", "iron_prow", "paddle_steamer"]
bp = {s: {} for s in STAGES}
cur = None


def put(r, y, f, state):
    bp[cur][(r, y, f)] = state


def stage(name):
    global cur
    cur = name


# ---------------------------------------------------------------- hull shape
STERN, BOW = -22, 18          # transom and stem
FC_START = 10                 # forecastle (raised fore deck) from here forward, deck at y=2
QD_END = -12                  # quarterdeck (cabin roof) from the stern to here, deck at y=4


def half_width(f):
    """Half-beam of the main deck line at station f."""
    if f < STERN or f > BOW:
        return -1
    if f >= 1:  # bow: an ogive running into the stem
        t = (f - 1) / (BOW - 1 + 0.6)
        return max(0, int(round(6 * math.sqrt(max(0.0, 1 - t * t)) - 0.15)))
    if f <= -18:  # stern quarters
        return {-22: 4, -21: 5, -20: 5, -19: 6, -18: 6}[f]
    return 6


def section(f, y):
    """Half-width of the hull at station f and height y (rounded bilge, narrower toward the keel)."""
    w = half_width(f)
    if w < 0:
        return -1
    shrink = {1: 0, 0: 0, -1: 0, -2: 1, -3: 2, -4: 4}.get(y, 99)
    # the bow and stern rise: their bottoms are higher
    bottom = -4
    if f >= 13:
        bottom = -4 + (f - 12)
    if f <= -20:
        bottom = -3
    if y < bottom:
        return -1
    return max(0, w - shrink) if y > -4 else max(0, min(1, w - shrink + 1))


def deck_y(f):
    if f >= FC_START:
        return 2
    return 0


def hull_cells():
    cells = {}
    for f in range(STERN, BOW + 1):
        top = deck_y(f)
        for y in range(-4, top + 1):
            w = section(f, min(y, 0))
            if w < 0:
                continue
            for r in range(-w, w + 1):
                cells[(r, y, f)] = True
    return cells


# ---------------------------------------------------------------- stages

def raft():
    stage("raft")
    for r in range(-3, 4):
        for f in range(-5, 6):
            put(r, 0, f, "minecraft:spruce_log[axis=z]" if abs(r) == 3 else DECK)


def expanded_hull():
    stage("expanded_hull")
    cells = hull_cells()

    def solid(r, y, f):
        return cells.get((r, y, f), False)
    for (r, y, f) in cells:
        top = deck_y(f)
        if y == top:
            edge = not solid(r + 1, y, f) or not solid(r - 1, y, f) or not solid(r, y, f + 1) and f < BOW or not solid(r, y, f - 1)
            put(r, y, f, HULL if edge and (abs(r) == half_width(f) or f in (STERN, BOW)) else (DECK if top == 0 else DECK2))
            continue
        outside = any(not solid(r + dr, y, f + df) for dr, df in ((1, 0), (-1, 0), (0, 1), (0, -1))) or not solid(r, y - 1, f)
        if not outside and f < BOW - 6:
            continue  # hollow hold (the enclosed air is what floats her)
        if y == -1:
            put(r, y, f, WALE if f not in (STERN, BOW) else HULL)
        else:
            put(r, y, f, HULL if y >= -1 else HULL_LOW)
    # forecastle front wall and the step up to it
    for r in range(-half_width(FC_START), half_width(FC_START) + 1):
        put(r, 1, FC_START, HULL)
    for r in (-1, 0, 1):
        put(r, 1, FC_START - 1, "minecraft:spruce_stairs[facing=north,half=bottom,shape=straight]")
    put(0, 1, FC_START, "minecraft:spruce_stairs[facing=north,half=bottom,shape=straight]")
    put(0, 2, FC_START, "minecraft:air")
    put(-1, 1, FC_START, "minecraft:spruce_stairs[facing=north,half=bottom,shape=straight]")
    put(1, 1, FC_START, "minecraft:spruce_stairs[facing=north,half=bottom,shape=straight]")
    # cargo hatch amidships
    for r in (-1, 0, 1):
        for f in (-7, -8):
            put(r, 0, f, "minecraft:spruce_trapdoor[facing=north,half=top,open=false]")
    # deck seams: darker planks along the centreline give the deck some lines
    for f in range(-10, FC_START - 1):
        if (0, 0, f) in bp[cur] and bp[cur][(0, 0, f)] == DECK and f not in (-7, -8, -2):
            put(0, 0, f, "minecraft:stripped_oak_log[axis=z]")
    lower_deck()


LD_FLOOR = -3                     # lower deck floor; you walk at y = -2 with the main deck (y = 0) overhead
LD_AFT, LD_FORE = STERN + 2, BOW - 7


def inside(r, y, f):
    """Inside the hull shell (not the shell itself) at this cell."""
    w = section(f, y)
    return w > 0 and abs(r) < w and LD_AFT <= f <= LD_FORE


def lower_deck():
    """A furnished deck inside the hull: crew quarters aft, mess, galley, the mast step, and the cargo hold forward."""
    def item(r, f, state, y=-2):
        if inside(r, y, f):
            put(r, y, f, state)

    # floor across the hold
    for f in range(LD_AFT, LD_FORE + 1):
        w = section(f, LD_FLOOR)
        for r in range(-w + 1, w):
            put(r, LD_FLOOR, f, DECK2)
    # portholes along both sides, just above the waterline
    for f in range(LD_AFT + 1, LD_FORE, 3):
        for side in (-1, 1):
            put(side * section(f, -1), -1, f, "minecraft:glass_pane")
    # companionway: stairs down from the main deck, forward of the mainmast on the starboard side
    for r in (2, 3):
        put(r, 0, 6, "minecraft:spruce_stairs[facing=south,half=bottom,shape=straight]")
        put(r, -1, 7, "minecraft:spruce_stairs[facing=south,half=bottom,shape=straight]")
        put(r, -2, 8, "minecraft:spruce_stairs[facing=south,half=bottom,shape=straight]")
        put(r, 0, 7, "minecraft:air")
        put(r, 0, 8, "minecraft:air")
        put(r, 1, 9, RAIL)
    for f in (7, 8):
        put(1, 1, f, RAIL)
        put(4, 1, f, RAIL)
    # lanterns hung from the deck beams
    for f in (-18, -13, -9, -3, 2, 7):  # never under the hatch: a lantern can't hang from a trapdoor
        put(0, -1, f, "minecraft:lantern[hanging=true]")

    # crew quarters (aft): bunks against the hull with sea chests between
    for f in (-19, -16, -13):
        item(-3, f, "minecraft:red_bed[facing=west,occupied=false,part=foot]")
        item(-4, f, "minecraft:red_bed[facing=west,occupied=false,part=head]")
        item(3, f, "minecraft:blue_bed[facing=east,occupied=false,part=foot]")
        item(4, f, "minecraft:blue_bed[facing=east,occupied=false,part=head]")
    for f in (-18, -15):
        item(-4, f, "minecraft:chest[facing=east,type=single,waterlogged=false]")
        item(4, f, "minecraft:chest[facing=west,type=single,waterlogged=false]")
    item(-4, -17, "minecraft:barrel[facing=up,open=false]")
    item(4, -17, "minecraft:barrel[facing=up,open=false]")

    for f in range(-20, -12):
        item(0, f, "minecraft:red_carpet")

    # mess: a long table down the middle with chairs
    for f in range(-11, -5):
        item(0, f, "minecraft:dark_oak_slab[type=top,waterlogged=false]")
        if f % 2:
            item(-1, f, "minecraft:spruce_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]")
            item(1, f, "minecraft:spruce_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]")
    item(-4, -10, "minecraft:bookshelf")
    item(-4, -9, "minecraft:bookshelf")
    item(4, -10, "minecraft:barrel[facing=up,open=false]")
    item(4, -9, "minecraft:barrel[facing=up,open=false]")

    # galley
    item(-4, -3, "minecraft:smoker[facing=east,lit=false]")
    item(-4, -2, "minecraft:furnace[facing=east,lit=false]")
    item(-4, -1, "minecraft:water_cauldron[level=3]")
    item(-4, 0, "minecraft:barrel[facing=up,open=false]")
    item(4, -3, "minecraft:crafting_table")
    item(4, -2, "minecraft:barrel[facing=up,open=false]")
    item(4, -1, "minecraft:brewing_stand[has_bottle_0=false,has_bottle_1=false,has_bottle_2=false]")

    # cargo hold (forward): chests and barrels stacked along the port side and forward bulkhead
    for f in range(4, LD_FORE + 1):
        for y in (-2, -1):
            r = -(section(f, y) - 1)
            if r < 0:
                item(r, f, "minecraft:barrel[facing=up,open=false]" if (f + y) % 2 else "minecraft:chest[facing=east,type=single,waterlogged=false]", y)
    for r in (-1, 0, 1):
        item(r, LD_FORE, "minecraft:barrel[facing=north,open=false]")
        item(r, LD_FORE, "minecraft:barrel[facing=north,open=false]", -1)


def gunwales():
    stage("gunwales")
    for f in range(STERN + 1, BOW):
        top = deck_y(f)
        w = half_width(f)
        for side in (-1, 1):
            r = side * w
            if f >= FC_START:
                put(r, top + 1, f, RAIL)
            else:
                put(r, 1, f, HULL)
                put(r, 2, f, RAIL if f % 4 else "minecraft:spruce_fence")
    # the bow and stern ends of the bulwark
    for r in range(-half_width(BOW - 1) + 1, half_width(BOW - 1)):
        put(r, deck_y(BOW - 1) + 1, BOW - 1, RAIL)
    for r in range(-half_width(STERN), half_width(STERN) + 1):
        put(r, 1, STERN, HULL)
        put(r, 2, STERN, RAIL)
    # forecastle front rail with a gap over the steps
    for r in range(-half_width(FC_START), half_width(FC_START) + 1):
        if abs(r) > 1:
            put(r, 3, FC_START, RAIL)
    # lanterns on the rail posts
    for f in (-10, -4, 2, 8):
        for side in (-1, 1):
            put(side * half_width(f), 3, f, LANTERN)
    # boarding gaps amidships
    for side in (-1, 1):
        for f in (-1, 0):
            put(side * 6, 1, f, "minecraft:air")
            put(side * 6, 2, f, "minecraft:air")


def square_sail(mast_f, base_y, yard_y, half, bulge_dir=1):
    """A yard with a billowing square sail hanging below it."""
    for r in range(-half - 1, half + 2):
        put(r, yard_y, mast_f, YARD if r != 0 else MAST)
    for y in range(base_y, yard_y):
        for r in range(-half, half + 1):
            if r == 0:
                continue
            depth = 1 if abs(r) <= half - 2 and base_y < y < yard_y - 1 else 0
            put(r, y, mast_f + depth * bulge_dir, SAIL)
    # sheets: fences from the clews down toward the rail
    for side in (-1, 1):
        put(side * half, base_y - 1, mast_f, RAIL)


def mainmast():
    stage("mainmast")
    mf = 3
    for y in range(1, 27):
        put(0, y, mf, MAST)
    for y in (LD_FLOOR + 1, LD_FLOOR + 2, 0):
        put(0, y, mf, "minecraft:dark_oak_log[axis=y]")
    square_sail(mf, 5, 13, 7)
    square_sail(mf, 15, 21, 5)
    for r in range(-3, 4):
        put(r, 25, mf, YARD if r else MAST)
    put(0, 27, mf, "minecraft:red_wool")
    put(0, 28, mf, "minecraft:red_wool")
    put(1, 28, mf, "minecraft:red_wool")
    # mast foot: a ring of trim
    for r, f in ((1, mf), (-1, mf), (0, mf + 1), (0, mf - 1)):
        put(r, 1, f, "minecraft:spruce_slab[type=bottom]")


def keel():
    stage("keel")
    for f in range(STERN + 1, BOW - 2):
        bottom = -4 + max(0, f - 12)
        put(0, bottom - 1, f, "minecraft:dark_oak_log[axis=z]")
    # stem post
    for y in range(-3, 3):
        put(0, y, BOW, "minecraft:dark_oak_log[axis=y]")
    # rudder hung on the transom
    for y in range(-4, 1):
        put(0, y, STERN - 1, "minecraft:dark_oak_planks")
    for y in range(-4, 0):
        put(0, y, STERN - 2, "minecraft:dark_oak_planks")
    put(0, 1, STERN - 1, "minecraft:dark_oak_fence")


def cabin():
    stage("cabin")
    f0, f1 = STERN + 1, QD_END
    for f in range(STERN, f1 + 1):
        w = half_width(f)
        for y in (1, 2, 3):
            for r in range(-w, w + 1):
                wall = abs(r) == w or f == f1 or f == STERN
                if not wall:
                    put(r, y, f, "minecraft:air")
                    continue
                state = HULL if y != 2 else HULL
                if f == f1 and y in (1, 2) and abs(r) <= 1 and r == 0:
                    continue  # door handled below
                if y == 2 and abs(r) == w and f % 3 == 0 and f not in (STERN, f1):
                    state = "minecraft:glass_pane"
                if f == f1 and y == 2 and abs(r) in (3, 4):
                    state = "minecraft:glass_pane"
                if f == STERN and y == 2 and abs(r) <= 2 and r != 0:
                    state = "minecraft:glass_pane"
                if (abs(r) == w and f == f1) or (abs(r) == w and f == STERN):
                    state = "minecraft:stripped_dark_oak_log[axis=y]"
                put(r, y, f, state)
        # quarterdeck floor (cabin roof)
        for r in range(-w, w + 1):
            put(r, 4, f, DECK2 if abs(r) < w else HULL)
        # quarterdeck rail
        for r in (-w, w):
            put(r, 5, f, RAIL)
    for r in range(-half_width(STERN), half_width(STERN) + 1):
        put(r, 5, STERN, RAIL)
    for r in range(-half_width(f1), half_width(f1) + 1):
        if abs(r) < 4:
            put(r, 5, f1, RAIL)
    # door
    put(0, 1, f1, "minecraft:spruce_door[facing=north,half=lower,hinge=left,open=false,powered=false]")
    put(0, 2, f1, "minecraft:spruce_door[facing=north,half=upper,hinge=left,open=false,powered=false]")
    # stairs up to the quarterdeck on both sides
    for side in (-1, 1):
        r = side * 5
        put(r, 1, f1 + 3, "minecraft:spruce_stairs[facing=south,half=bottom,shape=straight]")
        put(r, 2, f1 + 2, "minecraft:spruce_stairs[facing=south,half=bottom,shape=straight]")
        put(r, 3, f1 + 1, "minecraft:spruce_stairs[facing=south,half=bottom,shape=straight]")
        put(r, 1, f1 + 2, HULL)
        put(r, 1, f1 + 1, HULL)
        put(r, 2, f1 + 1, HULL)
        put(r, 5, f1, "minecraft:air")
    # furniture
    put(-4, 1, f0 + 1, "minecraft:chest[facing=east,type=single]")
    put(-4, 1, f0 + 2, "minecraft:barrel[facing=up,open=false]")
    put(-4, 1, f0 + 3, "minecraft:crafting_table")
    put(4, 1, f0 + 1, "minecraft:furnace[facing=west,lit=false]")
    put(4, 1, f0 + 2, "minecraft:smoker[facing=west,lit=false]")
    put(4, 1, f0 + 3, "minecraft:barrel[facing=up,open=false]")
    put(0, 3, f0 + 4, "minecraft:lantern[hanging=true]")
    put(-2, 1, f0 + 1, "minecraft:cartography_table")
    put(2, 1, f0 + 1, "minecraft:lectern[facing=north,has_book=false,powered=false]")
    # stern lanterns
    put(-half_width(STERN), 6, STERN, LANTERN)
    put(half_width(STERN), 6, STERN, LANTERN)


def crows_nest():
    stage("crows_nest")
    mf, y0 = 3, 22
    for r in range(-2, 3):
        for f in range(mf - 2, mf + 3):
            if abs(r) == 2 and abs(f - mf) == 2:
                continue
            if (r, f) != (0, mf):
                put(r, y0, f, "minecraft:spruce_slab[type=top]")
            edge = abs(r) == 2 or abs(f - mf) == 2
            if edge and (r, f) != (0, mf - 2):
                put(r, y0 + 1, f, RAIL)
    # ladder up the aft side of the mast
    for y in range(1, y0 + 1):
        put(0, y, mf - 1, "minecraft:ladder[facing=south]")
    put(0, y0 + 2, mf + 1, LANTERN)


def foremast():
    stage("foremast")
    mf = 13
    top = deck_y(mf)
    for y in range(top + 1, 22):
        put(0, y, mf, MAST)
    square_sail(mf, top + 4, top + 10, 5)
    square_sail(mf, top + 12, top + 17, 4)
    # bowsprit, rising forward
    for i, f in enumerate(range(BOW - 1, BOW + 8)):
        put(0, 3 + i // 3, f, "minecraft:spruce_log[axis=z]")
    tip_f, tip_y = BOW + 7, 3 + 8 // 3
    # jib: a triangle of sail from the foremast head down to the bowsprit
    head_y, head_f = 19, mf + 1
    for f in range(head_f + 1, tip_f):
        t = (f - head_f) / (tip_f - head_f)
        hi = int(round(head_y + (tip_y + 1 - head_y) * t))
        lo = 4 + (f - BOW) // 3 if f >= BOW else 4
        for y in range(lo + 1, hi):
            put(0, y, f, SAIL)


def iron_prow():
    stage("iron_prow")
    # ram at the waterline and a sheathed cutwater
    RAM = "minecraft:polished_deepslate"
    for f in range(BOW, BOW + 3):
        put(0, -2, f, RAM)
    put(0, -1, BOW + 1, RAM)
    for y in range(-1, 3):
        put(0, y, BOW + 1 if y < 0 else BOW, RAM)
    put(0, -2, BOW + 3, "minecraft:polished_deepslate_slab[type=top]")
    for f in range(BOW - 5, BOW):
        w = section(f, -2)
        for side in (-1, 1):
            if w > 0:
                put(side * w, -2, f, RAM)
                put(side * section(f, -1), -1, f, "minecraft:iron_bars")
    # anchors hanging off the bow
    for side in (-1, 1):
        r = side * (half_width(13) + 1)
        put(r, 1, 13, "minecraft:chain[axis=y]")
        put(r, 0, 13, "minecraft:chain[axis=y]")
        put(r, -1, 13, "minecraft:polished_deepslate")
        put(r, -2, 13, "minecraft:iron_bars")
        put(r, -2, 12, "minecraft:iron_bars")
        put(r, -2, 14, "minecraft:iron_bars")


def paddle_steamer():
    stage("paddle_steamer")
    cf, cy, radius = -4, -1, 4
    for side in (-1, 1):
        # wheel: rim, hub and eight paddles in the f-y plane, two blocks wide
        for dr in (7, 8):
            r = side * dr
            for df in range(-radius, radius + 1):
                for dy in range(-radius, radius + 1):
                    d = math.hypot(df, dy)
                    if abs(d - radius) < 0.55:
                        put(r, cy + dy, cf + df, "minecraft:stripped_spruce_log[axis=x]")
                    elif d < 0.5:
                        put(r, cy + dy, cf + df, "minecraft:dark_oak_log[axis=x]")
                    elif (df == 0 or dy == 0 or abs(df) == abs(dy)) and d < radius - 0.4:
                        put(r, cy + dy, cf + df, "minecraft:spruce_planks")
        # axle through the hull side
        put(side * 6, cy, cf, "minecraft:dark_oak_log[axis=x]")
        # paddle box: an arched housing over the top of the wheel
        for df in range(-radius - 1, radius + 2):
            for dy in range(0, radius + 2):
                d = math.hypot(df, dy)
                if abs(d - (radius + 1)) < 0.6 and cy + dy >= 1:
                    for dr in (6, 7, 8, 9):
                        put(side * dr, cy + dy, cf + df, HULL)
                elif d < radius + 0.5 and cy + dy >= 1:
                    put(side * 9, cy + dy, cf + df, HULL)
                    if dy >= 2:
                        put(side * 6, cy + dy, cf + df, HULL)
    # boiler house with twin funnels
    for r in range(-2, 3):
        for f in range(cf - 2, cf + 2):
            for y in (1, 2):
                edge = abs(r) == 2 or f in (cf - 2, cf + 1)
                put(r, y, f, "minecraft:bricks" if edge else "minecraft:air")
            put(r, 3, f, "minecraft:iron_block" if abs(r) < 2 and cf - 2 < f < cf + 1 else "minecraft:smooth_stone_slab[type=bottom]")
    put(0, 1, cf + 1, "minecraft:blast_furnace[facing=north,lit=false]")
    for r in (-1, 1):
        for y in range(4, 12):
            put(r, y, cf - 1, "minecraft:polished_blackstone_bricks" if y < 11 else "minecraft:polished_blackstone_brick_wall")
        put(r, 4, cf - 1, "minecraft:iron_block")
        put(r, 12, cf - 1, "minecraft:campfire[facing=north,lit=true,signal_fire=false,waterlogged=false]")


for fn in (raft, expanded_hull, gunwales, mainmast, keel, cabin, crows_nest, foremast, iron_prow, paddle_steamer):
    fn()

# ---------------------------------------------------------------- connections (fences, panes, bars, walls)
FULL = ("planks", "_log", "wool", "iron_block", "bricks", "furnace", "smoker", "barrel", "crafting_table", "cartography_table")


def family(state):
    n = state.split("[")[0]
    if n.endswith("_fence"):
        return "fence"
    if n.endswith("glass_pane") or n.endswith("iron_bars"):
        return "pane"
    if n.endswith("_wall"):
        return "wall"
    return None


def full(state):
    n = state.split("[")[0]
    return any(k in n for k in FULL) and "slab" not in n and "stairs" not in n


def connect(world):
    out = {}
    for (r, y, f), st in world.items():
        fam = family(st)
        if not fam or "[" in st and fam != "wall":
            out[(r, y, f)] = st
            continue
        props = {}
        for name, (dr, df) in (("north", (0, 1)), ("south", (0, -1)), ("east", (1, 0)), ("west", (-1, 0))):
            n = world.get((r + dr, y, f + df))
            ok = n is not None and (family(n) == fam or full(n))
            if fam == "wall":
                props[name] = "low" if ok else "none"
            else:
                props[name] = "true" if ok else "false"
        if fam == "wall":
            props["up"] = "true"
        props["waterlogged"] = "false"
        base = st.split("[")[0]
        out[(r, y, f)] = base + "[" + ",".join(f"{k}={v}" for k, v in sorted(props.items())) + "]"
    return out


# ---------------------------------------------------------------- write
os.makedirs(OUT, exist_ok=True)
world, stage_of, retired = {}, {}, []
for i, s in enumerate(STAGES):
    for k, v in bp[s].items():
        if k in world and world[k] != "minecraft:air":
            retired.append((k, world[k], stage_of[k], i))  # shown in earlier stages only
        world[k] = v
        stage_of[k] = i
for s in STAGES:
    blocks = [[r, y, f, st] for (r, y, f), st in sorted(bp[s].items())]
    with open(os.path.join(OUT, s + ".json"), "w") as fh:
        json.dump({"stage": s, "blocks": blocks}, fh, separators=(",", ":"))

world = {k: v for k, v in world.items() if v != "minecraft:air"}
world[CORE] = "tidebound:ship_core[facing=north]"
stage_of[CORE] = 0
shaped = connect(world)
preview = []
for (r, y, f), st in shaped.items():
    # deck coords -> block offsets from the core (Blueprint.rel, facing north)
    preview.append([r, y - 1, -(f - CORE[2]), stage_of[(r, y, f)], st])
for (r, y, f), st, s0, s1 in retired:
    if (r, y, f) != CORE:
        preview.append([r, y - 1, -(f - CORE[2]), s0, st, s1])
os.makedirs(os.path.join(ROOT, "run"), exist_ok=True)
with open(os.path.join(ROOT, "run", "design_preview.json"), "w") as fh:
    json.dump({"stages": STAGES, "blocks": preview}, fh)
print({s: len(bp[s]) for s in STAGES}, "total", len(world))
