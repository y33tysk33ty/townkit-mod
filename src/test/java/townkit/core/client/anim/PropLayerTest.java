package townkit.core.client.anim;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import townkit.core.anim.MoveFiles;

/**
 * The prop layer is in Towntopia's renderers only while it could draw something: with it there Towntopia's hidden-bone
 * skip is off for every villager, so it must not stay on because a prop is registered for a move nobody shows.
 */
class PropLayerTest {
    private static final ResourceLocation MARCH = ResourceLocation.parse("war:march");
    private static final ResourceLocation WAVE = ResourceLocation.parse("war:wave");
    private static final ResourceLocation WALK = ResourceLocation.parse("towntopia:walk");
    private static final ResourceLocation BANNER = ResourceLocation.parse("war:banner");
    private static final ResourceLocation FILE = ResourceLocation.parse("war:animations/march.animation.json");

    private static Moves moves(List<ResourceLocation> marchProps) {
        Moves m = new Moves();
        m.replace(Map.of(MARCH, new MoveFiles.Def(MARCH, FILE, "war.march", marchProps)));
        return m;
    }

    @Test
    void onlyVillagersWithPropMovesRefreshed() {
        Props props = new Props();
        Moves none = moves(List.of());
        assertFalse(PropLayer.needed(props, none, Set.of(MARCH), false), "nothing registered, no file props: no layer");

        props.addFor("war", MARCH, List.of(BANNER));
        assertFalse(PropLayer.needed(props, none, Set.of(), false), "a prop for a move nobody shows: no layer");
        assertFalse(PropLayer.needed(props, none, Set.of(WAVE), false), "villagers show another move: no layer");
        assertTrue(PropLayer.needed(props, none, Set.of(WAVE, MARCH), false), "a villager shows the prop's move: layer");
        assertTrue(PropLayer.needed(props, none, Set.of(), true), "a client move listener may pick any move: layer");
    }

    @Test
    void towntopiaMovesNeedNoLook() {
        Props props = new Props();
        props.addFor("war", WALK, List.of(BANNER));
        assertTrue(PropLayer.needed(props, moves(List.of()), Set.of(), false),
            "Towntopia's own moves are shown without any look: the layer is needed");
    }

    @Test
    void filePropsNeedTheMoveInUse() {
        Props props = new Props();
        Moves withProps = moves(List.of(BANNER));
        assertFalse(PropLayer.needed(props, withProps, Set.of(), false), "a move file with props that nobody plays: no layer");
        assertFalse(PropLayer.needed(props, withProps, Set.of(WAVE), false));
        assertTrue(PropLayer.needed(props, withProps, Set.of(MARCH), false), "played: layer");
        assertTrue(PropLayer.needed(props, withProps, Set.of(), true), "a client move listener may start it: layer");
    }
}
