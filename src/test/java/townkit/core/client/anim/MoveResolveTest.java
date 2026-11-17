package townkit.core.client.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import townkit.core.anim.MoveFiles;
import org.junit.jupiter.api.Test;

class MoveResolveTest {
    private static final ResourceLocation RAISE = ResourceLocation.parse("war:raise");
    private static final ResourceLocation RAISE_FILE = ResourceLocation.parse("war:animations/townkit/war.animation.json");
    private static final Function<String, String> TOWNTOPIA = state -> state.equals("SALUTE") ? "salute" : state.toLowerCase();
    private static final Set<String> BASE_CLIPS = Set.of("salute", "mixamo_idle");
    private static final BiPredicate<ResourceLocation, String> EXISTS = (file, clip) ->
        file.equals(MoveFiles.TOWNTOPIA_FILE) ? BASE_CLIPS.contains(clip) : file.equals(RAISE_FILE) && clip.equals("war.raise");

    private static Moves moves(Map<ResourceLocation, MoveFiles.Def> defs) {
        Moves m = new Moves();
        m.replace(defs);
        return m;
    }

    @Test
    void readsMoveFile() {
        Optional<MoveFiles.Def> d = MoveFiles.parse(RAISE, new StringReader(
            "{\"animation_file\": \"war:animations/townkit/war.animation.json\", \"animation\": \"war.raise\", \"props\": [\"war:banner\"]}"));
        assertTrue(d.isPresent());
        assertEquals(RAISE_FILE, d.get().animationFile());
        assertEquals("war.raise", d.get().animation());
        assertEquals(List.of(ResourceLocation.parse("war:banner")), d.get().props());
        assertTrue(MoveFiles.parse(RAISE, new StringReader("{\"animation\": \"x\"}")).isEmpty(), "no file: refused");
        assertTrue(MoveFiles.parse(RAISE, new StringReader("{\"animation_file\": \"war:a.json\"}")).isEmpty(), "no animation: refused");
        assertEquals(ResourceLocation.parse("war:sub/raise"), MoveFiles.idOf(ResourceLocation.parse("war:townkit/moves/sub/raise.json")));
    }

    @Test
    void unknownMoveFallsBack() {
        Moves m = moves(Map.of());
        ResourceLocation unknown = ResourceLocation.parse("war:nope");
        assertNull(m.resolve(unknown, TOWNTOPIA, EXISTS), "unknown move: Towntopia's animation plays");
        assertNull(m.resolve(unknown, TOWNTOPIA, EXISTS));
        assertEquals(1, m.warnings(), "warned once per move");

        Map<ResourceLocation, MoveFiles.Def> defs = new HashMap<>();
        ResourceLocation missing = ResourceLocation.parse("war:missing");
        defs.put(missing, new MoveFiles.Def(missing, ResourceLocation.parse("war:animations/gone.animation.json"), "war.gone", List.of()));
        ResourceLocation badClip = ResourceLocation.parse("war:bad_clip");
        defs.put(badClip, new MoveFiles.Def(badClip, RAISE_FILE, "war.nothing", List.of()));
        Moves m2 = moves(defs);
        assertNull(m2.resolve(missing, TOWNTOPIA, EXISTS), "missing animation file");
        assertNull(m2.resolve(badClip, TOWNTOPIA, EXISTS), "no such animation in the file");
        assertNull(m2.resolve(missing, TOWNTOPIA, EXISTS));
        assertEquals(2, m2.warnings());
        assertNull(m2.resolve(ResourceLocation.parse("towntopia:no_such_state"), TOWNTOPIA, EXISTS), "unknown Towntopia move");
        assertEquals(3, m2.warnings());
    }

    @Test
    void towntopiaMoveResolves() {
        Moves m = moves(Map.of(RAISE, new MoveFiles.Def(RAISE, RAISE_FILE, "war.raise", List.of())));
        assertEquals(new Moves.Clip(MoveFiles.TOWNTOPIA_FILE, "salute"), m.resolve(ResourceLocation.parse("towntopia:salute"), TOWNTOPIA, EXISTS));
        assertEquals(new Moves.Clip(RAISE_FILE, "war.raise"), m.resolve(RAISE, TOWNTOPIA, EXISTS), "addon move: its own file");
        assertEquals(0, m.warnings());
        assertEquals(Set.of(RAISE_FILE), m.animationFiles());
    }

    @Test
    void fallbacksSkipMissingFiles() {
        ResourceLocation flyguys = ResourceLocation.parse("towntopia:animations/flyguys_animations.animation.json");
        ResourceLocation gone = ResourceLocation.parse("war:animations/gone.animation.json");
        ResourceLocation[] towntopia = {flyguys};
        Moves m = new Moves();
        ResourceLocation[] out = m.fallbacks(towntopia, List.of(RAISE_FILE, gone, flyguys), f -> !f.equals(gone));
        assertEquals(List.of(flyguys, RAISE_FILE), List.of(out), "loaded addon files appended, a missing one left out, no duplicates");
        assertEquals(flyguys, out[0], "Towntopia's own files stay first");
        assertEquals(1, m.warnings(), "the missing file is logged");
        m.fallbacks(towntopia, List.of(RAISE_FILE, gone), f -> !f.equals(gone));
        assertEquals(1, m.warnings(), "once");
        assertTrue(List.of(m.fallbacks(towntopia, List.of(gone), f -> false)).equals(List.of(flyguys)), "only missing files: Towntopia's array");
    }
}
