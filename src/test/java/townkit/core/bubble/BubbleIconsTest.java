package townkit.core.bubble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import townkit.api.bubble.Valence;

class BubbleIconsTest {
    private static final ResourceLocation COIN = ResourceLocation.parse("myaddon:coin");
    private static final ResourceLocation COIN_PNG = ResourceLocation.parse("myaddon:textures/townkit/bubbles/coin.png");

    private final List<String> warnings = new ArrayList<>();
    private final BubbleIcons icons = new BubbleIcons(warnings::add);

    private Optional<BubbleIcons.Entry> parse(String json) {
        return icons.parse(COIN, new StringReader(json), t -> t.equals(COIN_PNG));
    }

    @Test
    void readsIconFile() {
        Optional<BubbleIcons.Entry> e = parse("{\"texture\": \"myaddon:textures/townkit/bubbles/coin.png\", \"valence\": \"positive\"}");
        assertEquals(Optional.of(new BubbleIcons.Entry(COIN, COIN_PNG, Valence.POSITIVE)), e);
        assertTrue(warnings.isEmpty(), warnings.toString());
        icons.replaceFiles(Map.of(COIN, e.orElseThrow()));
        assertEquals(e, icons.resolve(COIN));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void fileIdFromPath() {
        assertEquals(COIN, BubbleIcons.idOf(ResourceLocation.parse("myaddon:townkit/bubbles/coin.json")));
        assertEquals(ResourceLocation.parse("myaddon:moods/glad"), BubbleIcons.idOf(ResourceLocation.parse("myaddon:townkit/bubbles/moods/glad.json")));
    }

    @Test
    void badValenceRejectedWithLog() {
        assertEquals(Optional.empty(), parse("{\"texture\": \"myaddon:textures/townkit/bubbles/coin.png\", \"valence\": \"cheerful\"}"));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("myaddon:coin") && warnings.get(0).contains("cheerful"), warnings.get(0));
    }

    @Test
    void badFilesRejectedWithLog() {
        assertEquals(Optional.empty(), parse("{\"valence\": \"neutral\"}"));
        assertEquals(Optional.empty(), parse("not json {"));
        assertEquals(Optional.empty(), parse("{\"texture\": \"myaddon:textures/townkit/bubbles/missing.png\", \"valence\": \"neutral\"}"));
        assertEquals(3, warnings.size(), warnings.toString());
        assertTrue(warnings.get(2).contains("missing.png"), warnings.get(2));
    }

    @Test
    void unknownIdFallsBack() {
        ResourceLocation ghost = ResourceLocation.parse("myaddon:ghost");
        assertEquals(Optional.empty(), icons.resolve(ghost));
        assertEquals(Optional.empty(), icons.resolve(ghost));
        assertEquals(1, warnings.size(), "logged once per id: " + warnings);
        assertTrue(warnings.get(0).contains("myaddon:ghost"), warnings.get(0));
    }

    @Test
    void registrationKeepsOneValence() {
        icons.register(COIN, Valence.POSITIVE);
        icons.register(COIN, Valence.POSITIVE);
        assertEquals(Valence.POSITIVE, icons.registered(COIN));
        assertThrows(IllegalArgumentException.class, () -> icons.register(COIN, Valence.NEGATIVE));
    }

    @Test
    void fileValenceWinsOnClientWithLog() {
        icons.register(COIN, Valence.NEGATIVE);
        BubbleIcons.Entry e = new BubbleIcons.Entry(COIN, COIN_PNG, Valence.POSITIVE);
        icons.replaceFiles(Map.of(COIN, e));
        icons.replaceFiles(Map.of(COIN, e));
        assertEquals(Optional.of(e), icons.resolve(COIN));
        assertEquals(1, warnings.size(), "logged once: " + warnings);
        assertFalse(warnings.get(0).isEmpty());
    }
}
