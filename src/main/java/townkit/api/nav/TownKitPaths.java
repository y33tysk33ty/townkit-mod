package townkit.api.nav;

import java.util.Optional;
import net.minecraft.world.level.block.Block;
import townkit.api.Subscription;
import townkit.api.spi.TownKitBackend;
import townkit.api.spi.TownKitPathsView;

/**
 * Path rules: how a block's tiles change Towntopia's villager routes (cheaper, dearer, faster, or never walked on).
 * A rule applies to a tile whose surface block (the block the walker stands on) is the rule's block, in every village.
 *
 * <p>Who a rule reaches: cost and speed apply to villagers only, unless the rule has {@link PathRule#includeRaiders()},
 * which widens its <em>cost and speed</em> to raiders too. {@link PathRule#never()} ignores that toggle: a never tile
 * is refused to every walker that uses Towntopia's route map, raiders included.
 *
 * <p>Never tiles: Towntopia's building access and its piece-based reachability count a goal on a never tile, or a
 * place only behind never tiles, as unreachable. Other reachability answers may still say yes: Towntopia's
 * {@code NavReachability.canReach} falls back to a route search (which exempts the goal tile) or accepts a
 * neighbouring goal tile. The route search exempts the goal tile itself, so a direct move onto a never tile still
 * arrives; a villager standing on one can still walk off it. Smoothed routes don't cut across one.
 *
 * <p>Speed: a speed rule changes how fast a walker moves along its route line while standing on the block (a
 * Towntopia NPC reads the villager rules, any other walker the raider rules). Ladders, the first steps onto a route and
 * the walk's own run, jump and climb choices are unchanged.
 *
 * <p>Register at mod setup or later, from any thread. New route searches and walking speed see a change at once;
 * Towntopia's cached reachability (building access, "can this tile be reached") sees it when the surrounding route map
 * next updates (or rebuilds), not tile by tile at once. With no rule registered, Towntopia's routes cost what they did
 * before and villagers walk at their own speed. TownKit registers no rule of its own.
 *
 * @since 0.6.0
 */
public final class TownKitPaths {
    private TownKitPaths() {
    }

    private static TownKitPathsView view() {
        return TownKitBackend.get().paths();
    }

    /**
     * Applies {@code rule} to {@code block}'s tiles until the subscription is removed. Several rules on one block
     * combine (any never wins, then the lowest cost and the highest speed; see {@link PathRule}). {@code owner} names
     * the addon in logs. Throws IllegalArgumentException if {@code owner} is blank, NullPointerException on a null
     * argument.
     *
     * @since 0.6.0
     */
    public static Subscription rule(String owner, Block block, PathRule rule) {
        return view().rule(owner, block, rule);
    }

    /**
     * True while any path rule exists or any transport link is in a route map ({@link TownKitTransport}; a link that
     * is not available just now still counts), i.e. while Towntopia's route checks do more than their own work.
     *
     * @since 0.6.0
     */
    public static boolean active() {
        return view().active();
    }

    /**
     * The villager rule {@code block} has now, all its registered rules combined; empty when it has none. For
     * debugging: rules are combined again on every change. Throws NullPointerException on a null block.
     *
     * @since 0.6.0
     */
    public static Optional<PathRule> effective(Block block) {
        return view().effective(block);
    }
}
