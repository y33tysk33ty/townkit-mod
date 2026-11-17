package townkit.core.client.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import townkit.api.skin.SkinRef;

class SkinResolveTest {
    private static final ResourceLocation MEDIEVAL = ResourceLocation.parse("myaddon:medieval");
    private static final ResourceLocation ROYAL = ResourceLocation.parse("otheraddon:royal");
    private static final ResourceLocation MEDIEVAL_FOLDER = ResourceLocation.parse("myaddon:textures/townkit/looks/medieval/");
    private static final ResourceLocation MEDIEVAL_MERCHANT = ResourceLocation.parse("myaddon:textures/townkit/looks/medieval/dynamic_villagers_merchant.png");
    private static final ResourceLocation ENVOY_ID = ResourceLocation.parse("war:envoy");
    private static final ResourceLocation ENVOY_PNG = ResourceLocation.parse("war:textures/townkit/skins/envoy.png");
    private static final ResourceLocation ENVOY_GEO = ResourceLocation.parse("war:geo/townkit/skins/envoy.json");
    private static final Set<ResourceLocation> PRESENT = Set.of(ENVOY_PNG);

    private final List<String> warnings = new ArrayList<>();
    private final Looks looks = new Looks(warnings::add);
    private final Skins skins = new Skins(warnings::add);
    private final SkinResolve resolve = new SkinResolve(looks, skins, warnings::add);
    private final Map<String, ResourceLocation> swaps = new HashMap<>();

    /** Towntopia as the resolver sees it: its look in use is {@code current}; it has the skins in {@code keys}. */
    private static SkinResolve.Towntopia towntopia(String current, Set<String> keys) {
        return new SkinResolve.Towntopia() {
            @Override
            public boolean knows(String key) {
                return keys.contains(key);
            }

            @Override
            public ResourceLocation texture(String key, @Nullable String look) {
                String in = look == null ? current : look;
                String folder = in.equals("classic") ? "textures/entity/" : "textures/entity/" + in + "/";
                return ResourceLocation.fromNamespaceAndPath("towntopia", folder + "dynamic_villagers_" + key + ".png");
            }

            @Override
            public ResourceLocation inUse() {
                return ResourceLocation.fromNamespaceAndPath("towntopia", current);
            }
        };
    }

    private SkinResolve.Swaps swaps() {
        return (inUse, skin) -> swaps.get(inUse + ": " + skin);
    }

    private Looks.Def medieval() {
        return looks.parse(MEDIEVAL, new StringReader("{}"), f -> f.equals(MEDIEVAL_FOLDER) ? List.of(MEDIEVAL_MERCHANT) : List.of()).orElseThrow();
    }

    private Looks.Def royal() {
        return looks.parse(ROYAL, new StringReader("{}"), f -> List.of()).orElseThrow();
    }

    private void readEnvoy(String json) {
        Map<ResourceLocation, Looks.File> read = new HashMap<>();
        skins.parse(ENVOY_ID, new StringReader(json), PRESENT::contains).ifPresent(f -> read.put(ENVOY_ID, f));
        skins.replace(read);
    }

    @Test
    void towntopiaRefFollowsLook() {
        SkinResolve.Drawn d = resolve.resolve(SkinRef.towntopia("merchant"), null, swaps(), towntopia("rustic", Set.of("merchant")));
        assertEquals(ResourceLocation.parse("towntopia:textures/entity/rustic/dynamic_villagers_merchant.png"), d.texture());
        assertEquals("merchant", d.rig(), "Towntopia's rig key for the skin, so its look's own rig is drawn");
        assertNull(d.file(), "Towntopia's own file: its accessory pieces stay");
        SkinResolve.Drawn modern = resolve.resolve(SkinRef.towntopia("merchant"), null, swaps(), towntopia("modern", Set.of("merchant")));
        assertEquals(ResourceLocation.parse("towntopia:textures/entity/modern/dynamic_villagers_merchant.png"), modern.texture());
    }

    @Test
    void towntopiaRefUnderAddonLook() {
        Looks.Def medieval = medieval();
        Looks.Def royal = royal();
        looks.replace(Map.of(MEDIEVAL, medieval, ROYAL, royal));
        SkinResolve.Towntopia t = towntopia("rustic", Set.of("merchant"));
        SkinResolve.Drawn own = resolve.resolve(SkinRef.towntopia("merchant"), medieval, swaps(), t);
        assertEquals(MEDIEVAL_MERCHANT, own.texture(), "the chosen look's file for the skin");
        assertEquals(new Looks.File(MEDIEVAL_MERCHANT, null), own.file());
        assertNull(own.rig());
        SkinResolve.Drawn fallback = resolve.resolve(SkinRef.towntopia("merchant"), royal, swaps(), t);
        assertEquals(ResourceLocation.parse("towntopia:textures/entity/modern/dynamic_villagers_merchant.png"), fallback.texture(),
            "a look without the skin draws Modern's, as for any villager");
        assertEquals("merchant", fallback.rig());
        // a swap line while one of Towntopia's looks is in use puts the addon look's file on the override too
        swaps.put("towntopia:rustic: merchant", MEDIEVAL);
        SkinResolve.Drawn swapped = resolve.resolve(SkinRef.towntopia("merchant"), null, swaps(), t);
        assertEquals(MEDIEVAL_MERCHANT, swapped.texture());
    }

    @Test
    void addonSkinWithGeo() {
        readEnvoy("{\"texture\": \"war:textures/townkit/skins/envoy.png\", \"geo\": \"war:geo/townkit/skins/envoy.json\"}");
        SkinResolve.Drawn d = resolve.resolve(SkinRef.of(ENVOY_ID), null, swaps(), towntopia("rustic", Set.of()));
        assertEquals(ENVOY_PNG, d.texture());
        assertEquals(new Looks.File(ENVOY_PNG, ENVOY_GEO), d.file());
        assertNull(d.rig());
        // it wins over a chosen addon look too
        Looks.Def medieval = medieval();
        assertEquals(ENVOY_PNG, resolve.resolve(SkinRef.of(ENVOY_ID), medieval, swaps(), towntopia("rustic", Set.of())).texture());
        readEnvoy("{\"texture\": \"war:textures/townkit/skins/envoy.png\"}");
        assertEquals(new Looks.File(ENVOY_PNG, null), resolve.resolve(SkinRef.of(ENVOY_ID), null, swaps(), towntopia("rustic", Set.of())).file(),
            "no geo: Towntopia's base model");
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void unknownSkinFallsBack() {
        SkinResolve.Towntopia t = towntopia("rustic", Set.of("merchant"));
        assertNull(resolve.resolve(SkinRef.of(ResourceLocation.parse("war:nobody")), null, swaps(), t));
        assertNull(resolve.resolve(SkinRef.of(ResourceLocation.parse("war:nobody")), null, swaps(), t));
        assertEquals(1, warnings.size(), "warned once per id: " + warnings);
        assertNull(resolve.resolve(SkinRef.towntopia("no_such_skin"), null, swaps(), t), "a key Towntopia has no skin for");
        assertEquals(2, warnings.size(), warnings.toString());

        warnings.clear();
        readEnvoy("{\"texture\": \"war:textures/townkit/skins/missing.png\"}");
        assertEquals(1, warnings.size(), "a skin file whose texture is missing is refused: " + warnings);
        assertNull(resolve.resolve(SkinRef.of(ENVOY_ID), null, swaps(), t));
        readEnvoy("{\"texture\": \"war:textures/townkit/skins/envoy.png\", \"geo\": \"war:models/envoy.json\"}");
        assertNull(resolve.resolve(SkinRef.of(ENVOY_ID), null, swaps(), t), "a geo outside geo/ is refused (GeckoLib can't load it)");
        readEnvoy("[1]");
        assertNull(resolve.resolve(SkinRef.of(ENVOY_ID), null, swaps(), t));
        assertEquals(Optional.empty(), skins.parse(ResourceLocation.parse("towntopia:merchant"), new StringReader("{\"texture\": \"war:textures/townkit/skins/envoy.png\"}"),
            PRESENT::contains), "the towntopia namespace is Towntopia's own skins");

        warnings.clear();
        resolve.forget();
        assertNull(resolve.resolve(SkinRef.of(ResourceLocation.parse("war:nobody")), null, swaps(), t));
        assertEquals(1, warnings.size(), "warned again after a resource reload: " + warnings);
    }

    @Test
    void skinFileIds() {
        assertEquals(ENVOY_ID, Skins.idOf(ResourceLocation.parse("war:townkit/skins/envoy.json")));
    }
}
