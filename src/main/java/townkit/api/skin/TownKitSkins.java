package townkit.api.skin;

import java.util.List;
import java.util.Optional;
import net.minecraft.world.entity.Entity;
import townkit.api.Subscription;
import townkit.api.spi.TownKitBackend;
import townkit.api.spi.TownKitSkinsView;
import townkit.api.village.Villager;

/**
 * Villager skins: whole new looks from files, and skins for single villagers.
 *
 * <p><b>Looks.</b> A look file {@code assets/<ns>/townkit/looks/<name>.json} (in an addon's resources or a plain
 * resource pack; no code needed) adds the look {@code <ns>:<name>}:
 * <pre>{@code
 * {"name": "<translation key or text>",
 *  "folder": "<ns>:textures/townkit/looks/<name>/",
 *  "geo_folder": "<ns>:geo/townkit/looks/<name>/"}
 * }</pre>
 * Both folders are optional and default to the ones shown. The skins inside follow Towntopia's file names:
 * {@code dynamic_villagers_<skin>.png} (e.g. {@code dynamic_villagers_cook.png}, {@code dynamic_villagers_guard_2.png},
 * {@code dynamic_villagers_farmer_female_1.png} with an optional {@code _dirty} twin) and, for a body model of its
 * own, {@code <skin>.json} in the geo folder. A skin with no model of its own is drawn on Towntopia's base villager
 * model, without Towntopia's accessory pieces (hats, packs...), like Towntopia's London and Rustic looks.
 *
 * <p>Each player picks a look for themselves on the Theme page of the Mayor screen (TownKit adds a row of addon looks
 * there); it is saved in TownKit's client config ({@code townkit-client.toml}), never in Towntopia's, so Towntopia's
 * config stays valid without TownKit. A skin the chosen look has no file for is drawn as in Towntopia's Modern look.
 * Single skins can be swapped too, on the page of one look's skins (opened from a Theme page card): it offers each
 * addon look's version of the shown skin, in whichever look is in use, Towntopia's or an addon one; while an addon look
 * is in use, a Towntopia look's page wears its skin in the addon look. These swaps are also saved in TownKit's client
 * config (Towntopia's own swaps between its own looks stay in Towntopia's). A look needs TownKit on the client
 * that draws it; a client without TownKit ignores the files. A look whose files are gone (a removed pack) is ignored
 * and Towntopia's own choice is drawn.
 *
 * <p><b>Skins for single villagers.</b> Addon code gives one villager (or a group: envoys, soldiers) its own skin,
 * seen by every player and drawn instead of the player's look. A skin is a {@link SkinRef}: one of Towntopia's own
 * ({@code SkinRef.towntopia("merchant")}, which follows each player's look and swaps like Towntopia's own skins, with
 * the look's own body for that skin where it has one, e.g. Rustic's merchant), or an addon skin
 * ({@code SkinRef.of(<ns>:<name>)}) from a skin file {@code assets/<ns>/townkit/skins/<name>.json}:
 * <pre>{@code
 * {"texture": "<ns>:textures/townkit/skins/<name>.png",
 *  "geo": "<ns>:geo/townkit/skins/<name>.json"}
 * }</pre>
 * {@code geo} is optional (without it, Towntopia's base villager model, drawn without Towntopia's accessory pieces);
 * it must be under {@code geo/}. An addon skin looks the same whatever look a player picked. A player whose client
 * lacks the skin's files (unknown id, missing texture, or a model GeckoLib couldn't load) sees the villager as
 * without the skin; TownKit logs it once per id. Per entity the skin set last wins; removing it brings back the one
 * set before it. Each owner has one saved and one temporary skin per villager: setting again replaces that owner's
 * earlier one. A Towntopia farmer skin ({@code towntopia:farmer_*}) is always drawn clean (no dirt stages), and
 * Towntopia's accessory pieces (hats, packs) on a Towntopia skin still follow the villager's own profession, not the skin.
 *
 * <p>{@link #looks()} and {@link #currentLook()} are client only (on a dedicated server there are no looks); call them
 * from the client main thread. {@link #set}, {@link #setTemporary} and their subscriptions' {@code remove} are server
 * thread only (IllegalStateException elsewhere). Owners are non-blank strings, usually the addon's mod id.
 *
 * @since 0.7.0
 */
public final class TownKitSkins {
    private TownKitSkins() {
    }

    private static TownKitSkinsView view() {
        return TownKitBackend.get().skins();
    }

    /** Every addon look the client has a file for, by id. Empty on a dedicated server. */
    public static List<Look> looks() {
        return view().looks();
    }

    /**
     * The addon look this player has chosen, or empty when one of Towntopia's looks is in use (or the chosen look's
     * files are gone).
     */
    public static Optional<Look> currentLook() {
        return view().currentLook();
    }

    /**
     * Gives {@code villager} the skin {@code skin}, saved with the villager: it is drawn every time the villager
     * loads, after an unload, a change of dimension or a restart, until the subscription is removed (or the villager
     * is gone). Works while the villager is unloaded. A villager that no longer exists gets nothing (the subscription
     * does nothing; logged once). A body that is still joining its level gets the skin once the level holds it.
     * Subscriptions don't survive a restart: to switch a saved skin off later, call {@code set} again with the same
     * owner (it replaces your saved skin on that villager) and keep the new subscription.
     *
     * @throws IllegalArgumentException for a blank owner
     * @throws IllegalStateException    off the server thread
     * @since 0.7.0
     */
    public static Subscription set(String owner, Villager villager, SkinRef skin) {
        return view().set(owner, villager, skin);
    }

    /**
     * Gives one of Towntopia's people ({@code NpcEntity}: villagers, and the Mayor, visitors and nomads) the skin
     * {@code skin} while the entity exists: not saved, gone when it unloads, changes dimension or the server stops
     * (set it again when it comes back, e.g. from an {@code EntityJoinLevelEvent} listener). It may be called while the
     * entity joins its level or right after adding it: the skin is drawn once the level holds it. A removed entity, or
     * one not in its level within 30 seconds (never added), gets nothing; the subscription then does nothing (logged once).
     *
     * @throws IllegalArgumentException for a blank owner, or an entity that isn't one of Towntopia's people
     * @throws IllegalStateException    off the server thread, or for a client-side entity
     * @since 0.7.0
     */
    public static Subscription setTemporary(String owner, Entity entity, SkinRef skin) {
        return view().setTemporary(owner, entity, skin);
    }

    /**
     * The skin addon code gave this entity (the one that wins), or empty for none. On the server (server thread) the
     * skin set; on the client (main thread) the skin the server sent for it.
     *
     * @throws IllegalStateException for a server-side entity off the server thread
     * @since 0.7.0
     */
    public static Optional<SkinRef> of(Entity entity) {
        return view().of(entity);
    }
}
