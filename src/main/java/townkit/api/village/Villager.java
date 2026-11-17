package townkit.api.village;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import townkit.api.villager.VillagerDataKey;

/**
 * A Towntopia villager. Safe to keep: it holds the villager's id and reads Towntopia live on every call, so once the
 * villager is gone {@link #exists()} is false, {@link #activity()} is "none" and the optional answers are empty.
 * Server thread, except a villager from a client-side event, which only answers from its entity.
 *
 * @since 0.3.0
 */
public interface Villager {
    /**
     * Towntopia's own villager id (its npcId), not the entity's UUID. Stays the same across unloads.
     * Server only: Towntopia doesn't send it to clients, so on the client this is a random id that matches nothing
     * on the server; key client-side state by {@code entity().getUUID()} instead.
     */
    UUID id();

    boolean exists();

    /**
     * For example {@code towntopia:farmer}; {@code towntopia:none} without a job. Towntopia keeps the profession on the
     * entity only, so while the villager is unloaded this is {@code towntopia:none} too.
     */
    ResourceLocation profession();

    /** The current activity's name; "none" when idle or gone. */
    String activity();

    float hunger();

    float wellbeing();

    boolean sad();

    boolean moving();

    /** True while the villager's entity is in the world. */
    boolean loaded();

    Optional<BlockPos> position();

    /** The village that holds the villager now (it follows a villager that moves); empty for none, and always on the client. */
    Optional<Village> village();

    /** The villager's entity while it's loaded. */
    Optional<Entity> entity();

    /**
     * Change wellbeing (happiness) by {@code delta}, clamped by Towntopia to 0 to 100. Server thread.
     * Only while the villager is {@link #loaded() loaded}: Towntopia reads a villager's needs from its entity when it
     * loads and saves them back only while loaded, so a change to an unloaded villager could be lost. Returns false
     * (and changes nothing) otherwise; keep a change for later in your own villager data.
     *
     * @since 0.4.0
     */
    boolean addWellbeing(float delta);

    /**
     * Change hunger by {@code delta}, clamped to 0 to 100, where 100 is full and 0 is starving. Server thread, loaded
     * villagers only, like {@link #addWellbeing(float)}.
     *
     * @since 0.4.0
     */
    boolean addHunger(float delta);

    /**
     * This villager's data for {@code key}, saved with the world; works while the villager is unloaded. Empty on the
     * client, when there is none, or when the saved data can't be decoded.
     *
     * @since 0.4.0
     */
    <T> Optional<T> data(VillagerDataKey<T> key);

    /** Store data for {@code key}; false (nothing stored) on the client or once the server has stopped. @since 0.4.0 */
    <T> boolean setData(VillagerDataKey<T> key, T value);

    /** Remove this villager's data for {@code key}; false when there was none. @since 0.4.0 */
    boolean removeData(VillagerDataKey<?> key);
}
