package townkit.api.anim;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/**
 * Names a prop: a 3D piece (a banner, a tool) drawn on a villager's bone while a move plays, the way Towntopia's bard
 * carries a guitar. A prop file {@code assets/<ns>/townkit/props/<name>.json} adds the prop {@code <ns>:<name>}:
 * <pre>{@code
 * {"geo": "<ns>:geo/<file>.geo.json",
 *  "texture": "<ns>:textures/entity/<file>.png",
 *  "bone": "<villager bone>"}
 * }</pre>
 * {@code geo} is a GeckoLib model (e.g. made in Blockbench); every cube of it is drawn, placed at the pivot of the
 * villager's {@code bone} and moving with it. Use {@code bone2} for the right forearm (or {@code bone} for the left):
 * they are in every one of Towntopia's villager rigs (the base rig's {@code ITEM} bone is missing from the London and
 * Rustic ones). A prop is drawn while a move that carries it plays: a move file's {@code "props": [...]} list, or
 * {@link TownKitAnimations#withProps}. A client missing the prop's file, model or texture, or drawing a villager whose
 * model lacks the bone, draws the villager without it and logs it once per (prop, model). Props are drawn on
 * Towntopia's villagers, Mayor, visitors and nomads, not beyond 48 blocks from the camera.
 *
 * @since 0.7.0
 */
public record Prop(ResourceLocation id) {
    public Prop {
        Objects.requireNonNull(id, "id");
    }

    /** The prop with this id. */
    public static Prop of(ResourceLocation id) {
        return new Prop(id);
    }
}
