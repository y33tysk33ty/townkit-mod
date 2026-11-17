package townkit.api.nav;

import java.util.OptionalDouble;

/**
 * How one block changes villager pathing: a cost multiplier, a speed multiplier, or "never walk here". Immutable;
 * build one with a factory and refine it with the combinators.
 *
 * <p>Several rules on one block combine: any {@link #never()} wins, the lowest cost multiplier and the highest speed
 * multiplier apply. A speed multiplier also makes the tile cheaper to route through (cost divided by speed).
 *
 * <p>By default a rule applies to villagers only. {@link #includeRaiders()} widens the <em>cost and speed</em> to
 * raiders too. It does not change {@code never}: a "never" rule applies to every walker that uses the route map.
 *
 * @since 0.6.0
 */
public final class PathRule {
    private static final PathRule EMPTY = new PathRule(Double.NaN, false, Double.NaN, false);

    private final double cost;
    private final boolean never;
    private final double speed;
    private final boolean raiders;

    private PathRule(double cost, boolean never, double speed, boolean raiders) {
        this.cost = cost;
        this.never = never;
        this.speed = speed;
        this.raiders = raiders;
    }

    /** Walking over the block costs {@code multiplier} times as much to route through; must be above 0. */
    public static PathRule cost(double multiplier) {
        return EMPTY.withCost(multiplier);
    }

    /**
     * Villagers never route over the block (every walker using the route map). Building access and piece-based
     * reachability count a goal on such a tile, or a place only behind such tiles, as unreachable
     * ({@code NavReachability.canReach} may still answer yes through its route-search fallback or a neighbouring goal
     * tile); the route search still ends on one when it is the goal. A villager standing on one can still leave it.
     */
    public static PathRule never() {
        return new PathRule(Double.NaN, true, Double.NaN, false);
    }

    /** Walkers move {@code multiplier} times as fast on the block; must be above 0. Capped at 64x. */
    public static PathRule speed(double multiplier) {
        return EMPTY.withSpeed(multiplier);
    }

    /** This rule plus a speed multiplier (above 0). */
    public PathRule withSpeed(double multiplier) {
        return new PathRule(cost, never, check("speed", multiplier), raiders);
    }

    /** This rule plus a cost multiplier (above 0). */
    public PathRule withCost(double multiplier) {
        return new PathRule(check("cost", multiplier), never, speed, raiders);
    }

    /** This rule's cost and speed also apply to raiders. Does not affect {@code never}, which hits every walker. */
    public PathRule includeRaiders() {
        return new PathRule(cost, never, speed, true);
    }

    public OptionalDouble cost() {
        return Double.isNaN(cost) ? OptionalDouble.empty() : OptionalDouble.of(cost);
    }

    public boolean isNever() {
        return never;
    }

    public OptionalDouble speed() {
        return Double.isNaN(speed) ? OptionalDouble.empty() : OptionalDouble.of(speed);
    }

    public boolean appliesToRaiders() {
        return raiders;
    }

    private static double check(String what, double multiplier) {
        if (!(multiplier > 0) || Double.isInfinite(multiplier)) {
            throw new IllegalArgumentException(what + " multiplier must be above 0 and finite, was " + multiplier);
        }
        return multiplier;
    }

    @Override
    public String toString() {
        return "PathRule[cost=" + cost() + ", never=" + never + ", speed=" + speed() + ", raiders=" + raiders + "]";
    }
}
