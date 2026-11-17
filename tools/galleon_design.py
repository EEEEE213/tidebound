"""
The Galleon: Tidebound's final Ship Core upgrade. A three-masted pirate galleon with three decks
(main deck, gun deck, orlop), castles fore and aft, and a Jolly Roger at the main masthead.

Same deck coordinates as tools/ship_design.py: r = right, y = up (main deck = 0), f = forward; core at (0, 1, -2).
Imported by ship_design.py, which clears every earlier premade block the galleon doesn't reuse.
"""
import math

AIR = "minecraft:air"
HULL = "minecraft:dark_oak_planks"
HULL_LOW = "minecraft:spruce_planks"
STRIPE = "minecraft:stripped_spruce_log[axis=z]"
WALE = "minecraft:stripped_dark_oak_log[axis=z]"
DECK = "minecraft:spruce_planks"
TOPDECK = "minecraft:oak_planks"
GILT = "minecraft:yellow_terracotta"
MAST = "minecraft:dark_oak_log[axis=y]"
YARD = "minecraft:spruce_log[axis=x]"
SAIL = "minecraft:white_wool"
RAIL = "minecraft:dark_oak_fence"
ROPE = "minecraft:spruce_fence"
LANTERN = "minecraft:lantern[hanging=false]"
HANGING = "minecraft:lantern[hanging=true]"

STERN, BOW = -36, 40
FC = 22          # forecastle from here forward (deck y = 3, galley underneath)
QD = -18         # stern castle from the stern to here (great cabin y 1-3, quarterdeck y = 4)
POOP = -27       # poop cabin from the stern to here (y 5-7, poop deck y = 8)
GUN, ORLOP, BOTTOM = -4, -8, -9   # gun deck floor, orlop floor, hull bottom
MAIN_F, FORE_F, MIZZEN_F = 3, 26, -24
GUNS = range(-28, 33, 4)          # gunports along the gun deck

put = None


def half(f):
    if f < STERN or f > BOW:
        return -1
    if f >= 14:
        t = (f - 14) / (BOW - 14 + 0.6)
        return max(0, int(round(10 * math.sqrt(max(0.0, 1 - t * t)) - 0.1)))
    if f <= -31:
        return {-36: 7, -35: 8, -34: 9, -33: 9, -32: 10, -31: 10}[f]
    return 10


def bottom(f):
    b = BOTTOM
    if f >= 30:
        b = BOTTOM + (f - 29)
    if f <= -33:
        b = BOTTOM + (-32 - f) * 2
    return b


def sec(f, y):
    """Half-width of the hull at station f, height y: flat sides, a rounded bilge into a 13-wide bottom."""
    w = half(f)
    if w < 0 or y < bottom(f):
        return -1
    d = y - bottom(f)
    return max(0, w - {0: 4, 1: 2, 2: 1}.get(d, 0))


def top(f):
    """Height of the highest hull deck at station f."""
    if f >= FC:
        return 3
    if f <= POOP:
        return 8
    if f <= QD:
        return 4
    return 0


def floors(f):
    fl = {0, GUN, ORLOP}
    if f <= QD:
        fl.add(4)
    if f <= POOP:
        fl.add(8)
    if f >= FC:
        fl.add(3)
    return fl


def inside(r, y, f):
    w = sec(f, y)
    return w > 0 and abs(r) < w and y > bottom(f) and all(sec(f + d, y) >= 0 for d in (-1, 1))


def hull():
    for f in range(STERN, BOW + 1):
        for y in range(bottom(f), top(f) + 1):
            w = sec(f, y)
            if w < 0:
                continue
            for r in range(-w, w + 1):
                outer = (abs(r) == w or y == bottom(f) or f in (STERN, BOW)
                         or sec(f + 1, y) < abs(r) or sec(f - 1, y) < abs(r))
                if outer:
                    if y <= -6:
                        st = HULL_LOW
                    elif y == -5:
                        st = STRIPE if abs(r) == w else HULL_LOW
                    elif y == -1:
                        st = WALE if abs(r) == w else HULL
                    elif y in (4, 8) and abs(r) == w or y == 3 and f >= FC and abs(r) == w:
                        st = GILT
                    else:
                        st = HULL
                    put(r, y, f, st)
                elif y in floors(f):
                    put(r, y, f, TOPDECK if y == 0 and QD < f < FC else DECK)
                else:
                    put(r, y, f, AIR)


def windows():
    # stern windows: great cabin and poop cabin
    for y in (2, 3, 6, 7):
        for r in range(-half(STERN) + 1, half(STERN)):
            if abs(r) % 3 != 0:
                put(r, y, STERN, "minecraft:glass_pane")
    for f in range(STERN + 2, QD, 3):
        for side in (-1, 1):
            put(side * half(f), 2, f, "minecraft:glass_pane")
            if f <= POOP:
                put(side * half(f), 6, f, "minecraft:glass_pane")
    for f in range(FC + 2, BOW - 4, 4):
        for side in (-1, 1):
            put(side * sec(f, 1), 2, f, "minecraft:glass_pane")
    # portholes on the orlop, gunports on the gun deck
    for f in range(-26, 28, 5):
        for side in (-1, 1):
            put(side * sec(f, -6), -6, f, "minecraft:glass_pane")
    for f in GUNS:
        for side in (-1, 1):
            w = sec(f, -3)
            if w < 8:
                continue
            put(side * w, -3, f, f"minecraft:spruce_trapdoor[facing={'east' if side > 0 else 'west'},half=bottom,open=true]")
            put(side * (w - 1), -3, f, "minecraft:anvil[facing=north]")
            put(side * (w - 2), -3, f, "minecraft:dark_oak_slab[type=bottom]")


def stairs(r0, r1, f0, ytop, dirf):
    """A 4-step stairway down from the deck plane ytop, heading dirf (+1 bow / -1 stern)."""
    face = "south" if dirf > 0 else "north"
    for r in range(r0, r1 + 1):
        for i in range(4):
            put(r, ytop - i, f0 + i * dirf, f"minecraft:spruce_stairs[facing={face},half=bottom,shape=straight]")
            for y in range(ytop - i + 1, ytop + 1):
                put(r, y, f0 + i * dirf, AIR)
    # rails round the opening on the deck above
    for i in range(1, 4):
        put(r0 - 1, ytop + 1, f0 + i * dirf, RAIL)
        put(r1 + 1, ytop + 1, f0 + i * dirf, RAIL)
    for r in range(r0 - 1, r1 + 2):
        put(r, ytop + 1, f0 + 4 * dirf, RAIL)


def bed_at(r, y, f, colour, toward):
    dr, df = toward
    face = {(1, 0): "east", (-1, 0): "west", (0, 1): "north", (0, -1): "south"}[(dr, df)]
    put(r, y, f, f"minecraft:{colour}_bed[facing={face},occupied=false,part=foot]")
    put(r + dr, y, f + df, f"minecraft:{colour}_bed[facing={face},occupied=false,part=head]")


def table(r0, r1, f0, f1, y, seats=True):
    for r in range(r0, r1 + 1):
        for f in range(f0, f1 + 1):
            put(r, y, f, "minecraft:dark_oak_slab[type=top,waterlogged=false]")
    if seats:
        for f in range(f0, f1 + 1, 2):
            put(r0 - 1, y, f, "minecraft:spruce_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]")
            put(r1 + 1, y, f, "minecraft:spruce_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]")


def gun_deck():
    y = GUN + 1
    # crew bunks between the guns, a sea chest at each foot
    for f in range(6, 30, 4):
        w = sec(f, y)
        for side in (-1, 1):
            if w - 1 > 3:
                bed_at(side * (w - 2), y, f, "white", (side, 0))
                put(side * (w - 3), y, f, "minecraft:chest[facing=" + ("west" if side > 0 else "east") + ",type=single,waterlogged=false]")
    # powder barrels behind every other gun
    for i, f in enumerate(GUNS):
        w = sec(f, -3)
        if w >= 8 and i % 2 == 0:
            for side in (-1, 1):
                put(side * (w - 3), y, f, "minecraft:barrel[facing=up,open=false]")
    # capstan, carpenter's corner and a second mess table forward
    put(0, y, -4, "minecraft:dark_oak_log[axis=y]")
    put(0, y + 1, -4, "minecraft:spruce_slab[type=bottom]")
    for r, f in ((1, -4), (-1, -4), (0, -3), (0, -5)):
        put(r, y, f, ROPE)
    for r, f, st in ((-4, -1, "minecraft:crafting_table"), (-4, 0, "minecraft:stonecutter[facing=east]"),
                     (-4, 1, "minecraft:loom[facing=east]"), (4, -1, "minecraft:barrel[facing=up,open=false]"),
                     (4, 0, "minecraft:chest[facing=west,type=single,waterlogged=false]"), (4, 1, "minecraft:barrel[facing=up,open=false]")):
        put(r, y, f, st)
    table(-1, 1, 8, 12, y)
    put(0, -1, 10, HANGING)
    # the mess: a long table down the middle
    table(-1, 1, -22, -13, y)
    for f in (-22, -17, -13):
        put(0, -1, f, HANGING)
    # sick bay aft
    for f in (-31, -28):
        bed_at(-4, y, f, "light_gray", (-1, 0))
        bed_at(4, y, f, "light_gray", (1, 0))
    put(0, y, -31, "minecraft:brewing_stand[has_bottle_0=false,has_bottle_1=false,has_bottle_2=false]")
    put(-1, y, -31, "minecraft:water_cauldron[level=3]")
    put(1, y, -31, "minecraft:chest[facing=north,type=single,waterlogged=false]")
    # lights
    for f in range(-30, 34, 6):
        for r in (-5, 5):
            if inside(r, -1, f):
                put(r, -1, f, HANGING)
    # stairs: main deck -> gun deck, gun deck -> orlop
    stairs(4, 5, -12, 0, -1)
    stairs(-5, -4, 14, 0, 1)
    stairs(-5, -4, -6, GUN, -1)
    stairs(4, 5, 20, GUN, 1)


def orlop():
    y = ORLOP + 1
    # provisions aft: hay and barrels against the hull
    for f in range(-31, -21):
        w = sec(f, y)
        for side in (-1, 1):
            if w > 2:
                put(side * (w - 1), y, f, "minecraft:hay_block[axis=y]" if f % 2 else "minecraft:barrel[facing=up,open=false]")
                put(side * (w - 1), y + 1, f, "minecraft:barrel[facing=up,open=false]" if f % 2 else "minecraft:hay_block[axis=y]")
    # strongroom: iron bars front, chests inside, red carpet
    for r in range(-3, 4):
        for yy in (y, y + 1, y + 2):
            put(r, yy, -13, "minecraft:iron_bars" if not (r == 0 and yy < y + 2) else AIR)
    for f in range(-20, -13):
        put(0, y, f, "minecraft:red_carpet")
        for r in (-3, 3):
            put(r, y, f, "minecraft:chest[facing=" + ("east" if r < 0 else "west") + ",type=single,waterlogged=false]")
    put(0, y + 2, -17, HANGING)
    # cargo hold: two aisles of chests and barrels
    for f in range(-11, 13):
        if f in (2, 3, 4):
            continue
        for r in (-7, -3, 3, 7):
            if inside(r, y, f) and f % 3 != 0:
                put(r, y, f, "minecraft:barrel[facing=up,open=false]" if (f + r) % 2 else "minecraft:chest[facing=north,type=single,waterlogged=false]")
                put(r, y + 1, f, "minecraft:barrel[facing=up,open=false]")
    # armory
    put(-6, y, 15, "minecraft:anvil[facing=east]")
    put(-6, y, 16, "minecraft:smithing_table")
    put(-6, y, 17, "minecraft:grindstone[face=floor,facing=east]")
    put(-6, y, 18, "minecraft:fletching_table")
    put(6, y, 15, "minecraft:chest[facing=west,type=single,waterlogged=false]")
    put(6, y, 16, "minecraft:chest[facing=west,type=single,waterlogged=false]")
    put(6, y, 17, "minecraft:barrel[facing=up,open=false]")
    # brig on the port side: two cells behind iron bars
    for f in range(20, 28):
        for yy in (y, y + 1, y + 2):
            put(-3, yy, f, "minecraft:iron_bars" if f not in (21, 25) or yy == y + 2 else AIR)
    for yy in (y, y + 1, y + 2):
        put(-4, yy, 24, HULL)
        put(-5, yy, 24, HULL)
        put(-6, yy, 24, HULL)
    put(-6, y, 22, "minecraft:cobweb")
    put(-6, y, 26, "minecraft:chain[axis=y]")
    put(-6, y + 1, 26, "minecraft:chain[axis=y]")
    # lights
    for f in range(-28, 30, 6):
        for r in (-4, 4):
            if inside(r, y + 2, f):
                put(r, y + 2, f, HANGING)


def main_deck():
    # bulwarks round the waist, with deck cannons at the ports
    for f in range(QD + 1, FC):
        w = half(f)
        for side in (-1, 1):
            put(side * w, 1, f, HULL)
            put(side * w, 2, f, RAIL)
        if f in (-14, -8, 8, 16):
            for side in (-1, 1):
                put(side * w, 1, f, f"minecraft:spruce_trapdoor[facing={'east' if side > 0 else 'west'},half=bottom,open=true]")
                put(side * (w - 1), 1, f, "minecraft:anvil[facing=north]")
        if f in (-12, -4, 4, 12, 20):
            for side in (-1, 1):
                put(side * w, 3, f, LANTERN)
    # cargo gratings and the capstan
    for r in (-2, -1, 0, 1, 2):
        for f in (6, 7, 8, 9):
            put(r, 0, f, "minecraft:spruce_trapdoor[facing=north,half=top,open=false]")
    put(0, 1, -9, "minecraft:dark_oak_log[axis=y]")
    put(0, 2, -9, "minecraft:spruce_slab[type=bottom]")
    for r, f in ((1, -9), (-1, -9), (0, -8), (0, -10)):
        put(r, 1, f, ROPE)
    # deck cargo
    for r, f in ((-8, -2), (-8, -1), (-7, -2), (8, 2), (8, 1), (-8, 18), (8, 18)):
        put(r, 1, f, "minecraft:barrel[facing=up,open=false]")
    put(-8, 2, -2, "minecraft:barrel[facing=up,open=false]")
    # the ship's boat, upside down on the starboard side
    for f in range(11, 18):
        end = f in (11, 17)
        for r in (4, 6):
            if not end:
                put(r, 1, f, "minecraft:spruce_stairs[facing=" + ("west" if r == 4 else "east") + ",half=top,shape=straight]")
        put(5, 1, f, "minecraft:spruce_planks" if not end else "minecraft:spruce_stairs[facing=" + ("south" if f == 11 else "north") + ",half=top,shape=straight]")
        if not end:
            put(5, 2, f, "minecraft:spruce_slab[type=bottom]")
    # rope coils (barrels lying down) and cargo lashed by the masts
    for r, f in ((-2, 1), (2, 1), (-2, 5), (2, 5)):
        put(r, 1, f, "minecraft:barrel[facing=north,open=false]")
    for r, f in ((-7, 10), (-8, 10), (-8, 11), (-7, -6), (-8, -6), (7, -6), (8, -7)):
        put(r, 1, f, "minecraft:barrel[facing=up,open=false]")
    put(-8, 2, 10, "minecraft:barrel[facing=up,open=false]")
    put(8, 1, -2, "minecraft:hay_block[axis=x]")
    put(8, 1, -3, "minecraft:hay_block[axis=x]")
    # stairs up to the quarterdeck and the forecastle
    for side in (-1, 1):
        for i, f in enumerate((-14, -15, -16, -17)):
            for r in (side * 7, side * 8):
                for yy in range(1, i + 1):
                    put(r, yy, f, HULL)
                put(r, i + 1, f, "minecraft:spruce_stairs[facing=south,half=bottom,shape=straight]")
        for i, f in enumerate((19, 20, 21)):
            for r in (side * 6, side * 7):
                for yy in range(1, i + 1):
                    put(r, yy, f, HULL)
                put(r, i + 1, f, "minecraft:spruce_stairs[facing=north,half=bottom,shape=straight]")


def stern_castle():
    # front wall of the great cabin level, with doors and windows
    for r in range(-half(QD), half(QD) + 1):
        for y in (1, 2, 3):
            st = HULL
            if y == 2 and abs(r) in (3, 4):
                st = "minecraft:glass_pane"
            if r in (-1, 0) and y < 3:
                hinge = "left" if r == -1 else "right"
                st = f"minecraft:spruce_door[facing=north,half={'lower' if y == 1 else 'upper'},hinge={hinge},open=false,powered=false]"
            if abs(r) in (5, 9):
                st = "minecraft:stripped_dark_oak_log[axis=y]"
            put(r, y, QD, st)
    # bulkhead between the officers' cabins and the great cabin
    for r in range(-half(POOP) + 1, half(POOP)):
        for y in (1, 2, 3):
            put(r, y, POOP, AIR if r == 0 and y < 3 else HULL)
    put(0, 1, POOP, "minecraft:spruce_door[facing=north,half=lower,hinge=left,open=false,powered=false]")
    put(0, 2, POOP, "minecraft:spruce_door[facing=north,half=upper,hinge=left,open=false,powered=false]")
    # officers' cabins either side of a corridor
    for f in range(POOP + 1, QD):
        for side in (-1, 1):
            for y in (1, 2, 3):
                put(side * 2, y, f, HULL if f != -22 or y == 3 else AIR)
    for side in (-1, 1):
        put(side * 2, 1, -22, f"minecraft:spruce_door[facing={'east' if side > 0 else 'west'},half=lower,hinge=left,open=false,powered=false]")
        put(side * 2, 2, -22, f"minecraft:spruce_door[facing={'east' if side > 0 else 'west'},half=upper,hinge=left,open=false,powered=false]")
        bed_at(side * 8, 1, -25, "blue", (0, -1))
        put(side * 8, 1, -20, "minecraft:chest[facing=" + ("west" if side > 0 else "east") + ",type=single,waterlogged=false]")
        put(side * 4, 1, -25, "minecraft:barrel[facing=up,open=false]")
        put(side * 8, 1, -22, "minecraft:crafting_table" if side > 0 else "minecraft:bookshelf")
        put(side * 5, 3, -23, HANGING)
    put(0, 3, -20, HANGING)
    # the great cabin
    for f in range(STERN + 1, POOP):
        for r in range(-half(f) + 1, half(f)):
            put(r, 1, f, "minecraft:red_carpet")
    table(-1, 1, -32, -30, 1)
    put(0, 2, -31, LANTERN)
    put(-2, 1, -29, "minecraft:spruce_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]")
    bed_at(-7, 1, -33, "red", (0, -1))
    put(-8, 1, -31, "minecraft:chest[facing=east,type=single,waterlogged=false]")
    put(-8, 1, -30, "minecraft:chest[facing=east,type=single,waterlogged=false]")
    for f in range(-34, -28):
        for y in (1, 2):
            if f != -31:
                put(8, y, f, "minecraft:bookshelf")
    put(7, 1, -31, "minecraft:lectern[facing=west,has_book=false,powered=false]")
    put(5, 1, -28, "minecraft:cartography_table")
    put(-5, 1, -28, "minecraft:barrel[facing=up,open=false]")
    for f in (-34, -30):
        for r in (-4, 4):
            put(r, 3, f, HANGING)


def quarterdeck_and_poop():
    # quarterdeck rails, with gaps for the stairs from the main deck
    for f in range(POOP + 1, QD + 1):
        for side in (-1, 1):
            put(side * half(f), 5, f, RAIL)
    for r in range(-half(QD), half(QD) + 1):
        if abs(r) not in (7, 8):
            put(r, 5, QD, RAIL)
    # ship's wheel and bell
    put(0, 5, -21, "minecraft:dark_oak_fence")
    put(0, 6, -21, "minecraft:dark_oak_log[axis=z]")
    for r, y in ((-1, 6), (1, 6), (0, 7)):
        put(r, y, -21, ROPE)
    put(5, 5, -19, "minecraft:bell[attachment=floor,facing=north,powered=false]")
    # poop cabin (chart room)
    for r in range(-half(POOP), half(POOP) + 1):
        for y in (5, 6, 7):
            st = HULL
            if y == 6 and abs(r) in (3, 4, 6):
                st = "minecraft:glass_pane"
            if r == 0 and y < 7:
                st = f"minecraft:spruce_door[facing=north,half={'lower' if y == 5 else 'upper'},hinge=left,open=false,powered=false]"
            put(r, y, POOP, st)
    for f in range(STERN, POOP):
        w = half(f)
        for y in (5, 6, 7):
            put(-w, y, f, HULL if not (y == 6 and f % 3 == 0) else "minecraft:glass_pane")
            put(w, y, f, HULL if not (y == 6 and f % 3 == 0) else "minecraft:glass_pane")
            for r in range(-w + 1, w):
                put(r, y, f, AIR)
        for r in range(-w + 1, w):
            put(r, 8, f, DECK)
            if abs(r) % 3 and f == STERN:
                pass
    for r in range(-half(STERN), half(STERN) + 1):
        for y in (5, 6, 7):
            put(r, y, STERN, "minecraft:glass_pane" if y == 6 and abs(r) % 3 else HULL)
    put(-6, 5, -34, "minecraft:cartography_table")
    put(-6, 5, -33, "minecraft:lectern[facing=east,has_book=false,powered=false]")
    put(6, 5, -34, "minecraft:chest[facing=west,type=single,waterlogged=false]")
    put(6, 5, -33, "minecraft:chest[facing=west,type=single,waterlogged=false]")
    put(0, 5, -32, "minecraft:dark_oak_slab[type=top,waterlogged=false]")
    put(0, 5, -31, "minecraft:dark_oak_slab[type=top,waterlogged=false]")
    put(0, 7, -31, HANGING)
    # poop deck rails, stern lanterns and the ensign
    for f in range(STERN, POOP + 1):
        for side in (-1, 1):
            put(side * half(f), 9, f, RAIL)
    for r in range(-half(STERN), half(STERN) + 1):
        put(r, 9, STERN, RAIL)
    for r in (-half(STERN), 0, half(STERN)):
        put(r, 10, STERN, LANTERN)
    for r in range(-half(POOP), half(POOP) + 1):
        if abs(r) > 2:
            put(r, 9, POOP, RAIL)
    # stairs quarterdeck -> poop deck
    for side in (-1, 1):
        for i, f in enumerate((-23, -24, -25, -26)):
            r = side * 6
            for yy in range(5, 5 + i):
                put(r, yy, f, HULL)
            put(r, 5 + i, f, "minecraft:spruce_stairs[facing=south,half=bottom,shape=straight]")
    for y in range(9, 18):
        put(0, y, STERN - 1, RAIL)
    put(0, 8, STERN - 1, HULL)
    ensign = ["W...W", ".W.W.", "..W..", ".W.W.", "W...W"]  # black flag, white crossbones
    for row, line in enumerate(ensign):
        for col, ch in enumerate(line):
            put(0, 16 - row, STERN - 2 - col, "minecraft:white_wool" if ch == "W" else "minecraft:black_wool")


def forecastle():
    # back wall with two doors
    for r in range(-half(FC), half(FC) + 1):
        for y in (1, 2):
            st = HULL
            if abs(r) == 3:
                st = f"minecraft:spruce_door[facing=south,half={'lower' if y == 1 else 'upper'},hinge=left,open=false,powered=false]"
            elif y == 2 and abs(r) in (5, 6):
                st = "minecraft:glass_pane"
            put(r, y, FC, st)
    # galley underneath
    for i, f in enumerate(range(FC + 2, FC + 9)):
        w = sec(f, 1)
        put(-(w - 1), 1, f, "minecraft:smoker[facing=east,lit=false]" if i % 2 else "minecraft:furnace[facing=east,lit=false]")
        put(w - 1, 1, f, "minecraft:water_cauldron[level=3]" if i % 3 == 0 else "minecraft:barrel[facing=up,open=false]")
    put(1, 1, FC + 4, "minecraft:crafting_table")
    table(-1, 1, FC + 8, FC + 10, 1, seats=False)
    for f in (FC + 3, FC + 9):
        put(0, 2, f, HANGING)
    # rails, bell, anchors
    for f in range(FC, BOW):
        for side in (-1, 1):
            put(side * sec(f, 3), 4, f, RAIL)
    for r in range(-half(FC), half(FC) + 1):
        if abs(r) not in (6, 7):
            put(r, 4, FC, RAIL)
    put(0, 4, FC + 1, "minecraft:bell[attachment=floor,facing=east,powered=false]")
    for side in (-1, 1):
        r = side * (sec(33, 2) + 1)
        for y in (3, 2, 1, 0):
            put(r, y, 33, "minecraft:chain[axis=y]")
        put(r, -1, 33, "minecraft:polished_deepslate")
        put(r, -2, 33, "minecraft:polished_deepslate")
        for f in (32, 34):
            put(r, -2, f, "minecraft:iron_bars")
        put(r, -1, 32, "minecraft:iron_bars")
        put(r, -1, 34, "minecraft:iron_bars")
    # bone figurehead under the bowsprit
    for y in (0, 1, 2):
        put(0, y, BOW + 1, "minecraft:bone_block[axis=y]")
    put(0, 1, BOW + 2, "minecraft:bone_block[axis=z]")


def square_sail(mf, base, yard, h):
    for r in range(-h - 1, h + 2):
        put(r, yard, mf, YARD if r else MAST)
    for y in range(base, yard):
        for r in range(-h, h + 1):
            if r == 0:
                continue
            depth = 0
            if abs(r) <= h - 2 and base < y < yard - 1:
                depth = 1
            if abs(r) <= h - 5 and base + 1 < y < yard - 2:
                depth = 2
            put(r, y, mf + depth, SAIL)


def platform(mf, y, size):
    for r in range(-size, size + 1):
        for f in range(mf - size, mf + size + 1):
            if abs(r) == size and abs(f - mf) == size:
                continue
            if (r, f) != (0, mf):
                put(r, y, f, "minecraft:spruce_slab[type=top]")
            if (abs(r) == size or abs(f - mf) == size) and (r, f) != (0, mf - size):
                put(r, y + 1, f, RAIL)


def shroud(mf, y_hi, r_lo, y_lo, side):
    """A rope from just under a yard down to the rail, drawn as a stair-stepped line of fence."""
    steps = max(abs(r_lo - 1), y_hi - y_lo)
    for i in range(steps + 1):
        t = i / steps
        r = side * int(round(1 + (r_lo - 1) * t))
        y = int(round(y_hi - (y_hi - y_lo) * t))
        put(r, y, mf - 1, ROPE)


def rigging():
    # mainmast with the Jolly Roger at the head
    for y in range(ORLOP + 1, 51):
        put(0, y, MAIN_F, MAST)
    square_sail(MAIN_F, 8, 18, 9)
    platform(MAIN_F, 19, 2)
    square_sail(MAIN_F, 21, 29, 7)
    square_sail(MAIN_F, 31, 38, 5)
    platform(MAIN_F, 39, 1)
    for y in range(9, 18):
        put(0, y, MAIN_F - 1, "minecraft:ladder[facing=south]")
    for y in range(20, 39):
        put(0, y, MAIN_F - 1, "minecraft:ladder[facing=south]")
    jolly = [
        "...........",
        "....WWW....",
        "...WWWWW...",
        "...W.W.W...",
        "...WWWWW...",
        "....W.W....",
        ".WW.....WW.",
        "...WWWWW...",
        ".WW.....WW.",
        "...........",
    ]
    for row, line in enumerate(jolly):
        y = 49 - row
        put(0, y, MAIN_F - 1, ROPE)
        for col, ch in enumerate(line):
            put(0, y, MAIN_F - 2 - col, "minecraft:white_wool" if ch == "W" else "minecraft:black_wool")
    # foremast
    for y in range(GUN + 1, 44):
        put(0, y, FORE_F, MAST)
    square_sail(FORE_F, 8, 17, 8)
    platform(FORE_F, 18, 1)
    square_sail(FORE_F, 20, 27, 6)
    square_sail(FORE_F, 29, 35, 4)
    put(0, 44, FORE_F, "minecraft:red_wool")
    put(0, 45, FORE_F, "minecraft:red_wool")
    put(0, 45, FORE_F - 1, "minecraft:red_wool")
    put(0, 45, FORE_F - 2, "minecraft:red_wool")
    # mizzen: a spanker aft of the mast and a small topsail
    for y in range(GUN + 1, 38):
        put(0, y, MIZZEN_F, MAST)
    for f in range(MIZZEN_F - 1, STERN - 1, -1):
        put(0, 11, f, "minecraft:spruce_log[axis=z]")  # boom
        hi = 22 - (MIZZEN_F - 1 - f) // 2
        put(0, hi + 1, f, "minecraft:spruce_log[axis=z]")  # gaff
        for y in range(12, hi + 1):
            put(0, y, f, SAIL)
    square_sail(MIZZEN_F, 26, 31, 4)
    put(0, 38, MIZZEN_F, "minecraft:red_wool")
    put(0, 38, MIZZEN_F - 1, "minecraft:red_wool")
    # bowsprit and jibs
    for i, f in enumerate(range(BOW - 2, BOW + 13)):
        put(0, 4 + i // 3, f, "minecraft:spruce_log[axis=z]")
    tip_f, tip_y = BOW + 12, 4 + 14 // 3
    for head_y, lo_f in ((33, BOW + 1), (24, FORE_F + 3)):
        head_f = FORE_F + 1
        for f in range(max(head_f + 1, lo_f), tip_f):
            t = (f - head_f) / (tip_f - head_f)
            hi = int(round(head_y + (tip_y + 1 - head_y) * t))
            lo = 5 + max(0, f - (BOW - 2)) // 3 if f >= BOW - 2 else 5
            if head_y == 24:
                lo = max(lo, 6)
            for y in range(lo + 1, hi):
                put(1 if head_y == 24 else 0, y, f, SAIL)
    # forestays from the foremast to the bowsprit (the jibs hang under them)
    for head_y, end_f in ((33, tip_f), (24, BOW + 4)):
        end_y = 4 + (end_f - (BOW - 2)) // 3 + 1
        n = max(end_f - (FORE_F + 1), head_y - end_y)
        for i in range(n + 1):
            t = i / n
            f = int(round(FORE_F + 1 + (end_f - FORE_F - 1) * t))
            y = int(round(head_y + (end_y - head_y) * t))
            put(0 if head_y == 33 else 1, y, f, ROPE)
    # shrouds down to the rails
    shroud(MAIN_F, 17, 10, 2, 1)
    shroud(MAIN_F, 17, 10, 2, -1)
    shroud(FORE_F, 16, sec(FORE_F - 1, 3), 4, 1)
    shroud(FORE_F, 16, sec(FORE_F - 1, 3), 4, -1)
    shroud(MIZZEN_F + 2, 25, 10, 5, 1)
    shroud(MIZZEN_F + 2, 25, 10, 5, -1)
    # rudder
    for y in range(BOTTOM + 1, 2):
        f = min(f for f in range(STERN, BOW) if sec(f, y) >= 0)
        put(0, y, f - 1, HULL)
    for f in range(-31, 30):
        put(0, bottom(f) - 1, f, "minecraft:dark_oak_log[axis=z]")  # keel


def build(put_fn):
    global put
    put = put_fn
    hull()
    windows()
    main_deck()
    gun_deck()
    orlop()
    stern_castle()
    quarterdeck_and_poop()
    forecastle()
    rigging()
