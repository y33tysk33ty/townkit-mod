package townkit.core.client.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class PropsTest {
    private static final ResourceLocation BANNER = ResourceLocation.parse("war:banner");
    private static final ResourceLocation GEO = ResourceLocation.parse("war:geo/banner.geo.json");
    private static final ResourceLocation TEXTURE = ResourceLocation.parse("war:textures/entity/banner.png");
    private static final ResourceLocation BASE = ResourceLocation.parse("towntopia:geo/base_villager.json");
    private static final ResourceLocation RUSTIC = ResourceLocation.parse("towntopia:geo/rustic/guard.json");
    private static final ResourceLocation MARCH = ResourceLocation.parse("war:march");
    private static final Predicate<ResourceLocation> ALL = r -> true;

    private static Props props() {
        Props p = new Props();
        p.replace(Map.of(BANNER, new Props.Def(BANNER, GEO, TEXTURE, "bone2")));
        return p;
    }

    @Test
    void readsPropFile() {
        Optional<Props.Def> d = Props.parse(BANNER, new StringReader(
            "{\"geo\": \"war:geo/banner.geo.json\", \"texture\": \"war:textures/entity/banner.png\", \"bone\": \"bone2\"}"));
        assertTrue(d.isPresent());
        assertEquals(new Props.Def(BANNER, GEO, TEXTURE, "bone2"), d.get());
        assertEquals(BANNER, Props.idOf(ResourceLocation.parse("war:townkit/props/banner.json")));
        assertFalse(Props.parse(BANNER, new StringReader("{\"geo\": \"war:geo/banner.geo.json\", \"bone\": \"bone2\"}")).isPresent(),
            "a prop file without a texture is ignored");
        assertFalse(Props.parse(BANNER, new StringReader("not json")).isPresent());
    }

    @Test
    void missingBoneSkipsAndLogsOnce() {
        Props p = props();
        Predicate<String> noBone = b -> false;
        assertNull(p.drawable(BANNER, BASE, noBone, ALL, ALL), "a model without the bone: not drawn");
        assertNull(p.drawable(BANNER, BASE, noBone, ALL, ALL));
        assertEquals(1, p.warnings(), "logged once per (prop, model)");
        assertNull(p.drawable(BANNER, RUSTIC, noBone, ALL, ALL));
        assertEquals(2, p.warnings(), "another model logs once more");
        assertNotNull(p.drawable(BANNER, BASE, Set.of("bone2")::contains, ALL, ALL), "a model with the bone draws it");
    }

    @Test
    void missingArtSkipsAndLogsOnce() {
        Props p = props();
        Predicate<String> bone = Set.of("bone2")::contains;
        assertNull(p.drawable(BANNER, BASE, bone, r -> false, ALL), "geo not loaded");
        assertNull(p.drawable(BANNER, BASE, bone, r -> false, ALL));
        assertNull(p.drawable(BANNER, BASE, bone, ALL, r -> false), "texture missing");
        assertNull(p.drawable(ResourceLocation.parse("war:nothing"), BASE, bone, ALL, ALL), "no prop file");
        assertNull(p.drawable(ResourceLocation.parse("war:nothing"), BASE, bone, ALL, ALL));
        assertEquals(2, p.warnings(), "once per (prop, model): banner on base, nothing on base");
    }

    @Test
    void registeredPropsAddToFileProps() {
        Props p = props();
        ResourceLocation flag = ResourceLocation.parse("war:flag");
        assertEquals(List.of(BANNER), p.propsOf(MARCH, List.of(BANNER)));
        long s = p.addFor("war", MARCH, List.of(flag, BANNER));
        assertTrue(p.anyRegistered());
        assertEquals(List.of(BANNER, flag), p.propsOf(MARCH, List.of(BANNER)), "file props first, no duplicates");
        assertEquals(List.of(), p.propsOf(ResourceLocation.parse("towntopia:walk"), List.of()));
        assertTrue(p.remove(s));
        assertFalse(p.anyRegistered());
        assertEquals(List.of(BANNER), p.propsOf(MARCH, List.of(BANNER)));
    }
}
