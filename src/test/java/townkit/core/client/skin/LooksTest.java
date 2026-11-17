package townkit.core.client.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import townkit.api.skin.Look;
import townkit.core.hook.HookManifest;

class LooksTest {
    private static final ResourceLocation MEDIEVAL = ResourceLocation.parse("myaddon:medieval");
    private static final ResourceLocation ROYAL = ResourceLocation.parse("otheraddon:royal");
    private static final ResourceLocation FOLDER = ResourceLocation.parse("myaddon:textures/townkit/looks/medieval/");
    private static final ResourceLocation GEO_FOLDER = ResourceLocation.parse("myaddon:geo/townkit/looks/medieval/");
    private static final ResourceLocation FARMER_PNG = png("myaddon", "medieval", "dynamic_villagers_farmer_female_1");
    private static final ResourceLocation FARMER_DIRTY_PNG = png("myaddon", "medieval", "dynamic_villagers_farmer_female_1_dirty");
    private static final ResourceLocation COOK_PNG = png("myaddon", "medieval", "dynamic_villagers_cook");
    private static final ResourceLocation COOK_GEO = ResourceLocation.parse("myaddon:geo/townkit/looks/medieval/cook.json");
    private static final ResourceLocation ROYAL_COOK_PNG = png("otheraddon", "royal", "dynamic_villagers_cook");

    private final List<String> warnings = new ArrayList<>();
    private final Looks looks = new Looks(warnings::add);

    private static ResourceLocation png(String ns, String look, String file) {
        return ResourceLocation.fromNamespaceAndPath(ns, "textures/townkit/looks/" + look + "/" + file + ".png");
    }

    /** Lists what's in the two medieval folders and the royal folder, as the resource manager would. */
    private static List<ResourceLocation> files(ResourceLocation folder) {
        if (folder.equals(FOLDER)) return List.of(FARMER_PNG, FARMER_DIRTY_PNG, COOK_PNG);
        if (folder.equals(GEO_FOLDER)) return List.of(COOK_GEO);
        if (folder.equals(ResourceLocation.parse("otheraddon:textures/townkit/looks/royal/"))) return List.of(ROYAL_COOK_PNG);
        return List.of();
    }

    private Looks.Def medieval() {
        return looks.parse(MEDIEVAL, new StringReader("{\"name\": \"Medieval\", \"folder\": \"myaddon:textures/townkit/looks/medieval/\","
            + " \"geo_folder\": \"myaddon:geo/townkit/looks/medieval/\"}"), LooksTest::files).orElseThrow();
    }

    private Looks.Def royal() {
        return looks.parse(ROYAL, new StringReader("{\"name\": \"look.otheraddon.royal\"}"), LooksTest::files).orElseThrow();
    }

    @Test
    void readsLookFile() {
        assertEquals(MEDIEVAL, Looks.idOf(ResourceLocation.parse("myaddon:townkit/looks/medieval.json")));
        Looks.Def d = medieval();
        assertEquals(new Look(MEDIEVAL, Component.translatable("Medieval")), d.look());
        assertEquals(new Looks.File(COOK_PNG, COOK_GEO), d.file(new Looks.SkinName("cook", false)));
        assertEquals(new Looks.File(FARMER_PNG, null), d.file(new Looks.SkinName("farmer_female_1", false)));
        assertEquals(new Looks.File(FARMER_DIRTY_PNG, null), d.file(new Looks.SkinName("farmer_female_1", true)));
        // a dirty skin the look has no dirty file for stays clean
        assertEquals(new Looks.File(COOK_PNG, COOK_GEO), d.file(new Looks.SkinName("cook", true)));
        assertNull(d.file(new Looks.SkinName("guard_1", false)));
        assertTrue(warnings.isEmpty(), warnings.toString());

        // folders default to the look's own; the name is a translation key or plain text
        Looks.Def r = royal();
        assertEquals(new Look(ROYAL, Component.translatable("look.otheraddon.royal")), r.look());
        assertEquals(new Looks.File(ROYAL_COOK_PNG, null), r.file(new Looks.SkinName("cook", false)));

        looks.replace(Map.of(MEDIEVAL, d, ROYAL, r));
        assertEquals(List.of(d.look(), r.look()), looks.looks());
        assertEquals(Optional.of(d), looks.get(MEDIEVAL));

        // bad files are ignored with a warning, never thrown
        assertTrue(looks.parse(MEDIEVAL, new StringReader("[1, 2]"), LooksTest::files).isEmpty());
        assertTrue(looks.parse(MEDIEVAL, new StringReader("{\"name\": \"x\", \"folder\": \"Not A Valid Id\"}"), LooksTest::files).isEmpty());
        assertTrue(looks.parse(ResourceLocation.parse("towntopia:modern"), new StringReader("{}"), LooksTest::files).isEmpty());
        assertEquals(3, warnings.size(), warnings.toString());
    }

    @Test
    void readsTowntopiaSkinNames() {
        assertEquals(new Looks.SkinName("guard_2", false), Looks.skinName(ResourceLocation.parse("towntopia:textures/entity/modern/dynamic_villagers_guard_2.png")));
        assertEquals(new Looks.SkinName("cook", false), Looks.skinName(ResourceLocation.parse("towntopia:textures/entity/dynamic_villagers_cook.png")));
        assertEquals(new Looks.SkinName("farmer_male_2", true),
            Looks.skinName(ResourceLocation.parse("towntopia:textures/entity/rustic/dynamic_villagers_farmer_male_2_dirty.png")));
        // Towntopia's generated in-between dirt stages: 1 and 2 count as clean, 3 as dirty
        assertEquals(new Looks.SkinName("farmer_female_1", false), Looks.skinName(ResourceLocation.parse("towntopia:dirt_stage/modern/farmer_female_1_2")));
        assertEquals(new Looks.SkinName("farmer_gay_1", true), Looks.skinName(ResourceLocation.parse("towntopia:dirt_stage/farmer_gay_1_3")));
        assertNull(Looks.skinName(ResourceLocation.parse("towntopia:textures/entity/something_else.png")));
    }

    @Test
    void missingSkinFallsBackToModern() {
        Looks.Def d = medieval();
        looks.replace(Map.of(MEDIEVAL, d));
        assertEquals(new Looks.File(FARMER_PNG, null), looks.pick(d, new Looks.SkinName("farmer_female_1", false), null));
        assertEquals(Looks.Towntopia.MODERN, looks.pick(d, new Looks.SkinName("guard_1", false), null));
        assertEquals(Looks.Towntopia.MODERN, looks.pick(d, new Looks.SkinName("mayor", false), null));
    }

    @Test
    void swapLineWins() {
        Looks.Def d = medieval();
        Looks.Def r = royal();
        looks.replace(Map.of(MEDIEVAL, d, ROYAL, r));
        Map<ResourceLocation, Map<String, ResourceLocation>> swaps = LookChoice.index(List.of(
            "myaddon:medieval: cook = otheraddon:royal",
            "myaddon:medieval: farmer_female_1 = towntopia:rustic",
            "myaddon:medieval: guard_1 = otheraddon:royal",
            "myaddon:medieval: cook_2 = gone:pack",
            "not a swap line"), warnings::add);
        assertEquals(1, warnings.size(), warnings.toString());
        Map<String, ResourceLocation> mine = swaps.get(MEDIEVAL);
        // another addon look's file
        assertEquals(new Looks.File(ROYAL_COOK_PNG, null), looks.pick(d, new Looks.SkinName("cook", false), mine.get("cook")));
        // one of Towntopia's looks
        assertEquals(new Looks.Towntopia("rustic"), looks.pick(d, new Looks.SkinName("farmer_female_1", false), mine.get("farmer_female_1")));
        // a swap to a look without that skin: the chosen look, then Modern
        assertEquals(Looks.Towntopia.MODERN, looks.pick(d, new Looks.SkinName("guard_1", false), mine.get("guard_1")));
        assertEquals(Looks.Towntopia.MODERN, looks.pick(d, new Looks.SkinName("cook_2", false), mine.get("cook_2")));

        // lines are written back in the same form, one per (look in use, skin)
        List<String> lines = LookChoice.withSwap(List.of("myaddon:medieval: cook = otheraddon:royal"), MEDIEVAL, "cook", ResourceLocation.parse("towntopia:london"));
        assertEquals(List.of("myaddon:medieval: cook = towntopia:london"), lines);
        assertEquals(List.of(), LookChoice.withSwap(lines, MEDIEVAL, "cook", null));
    }

    @Test
    void swapLineWhileTowntopiaLookInUse() {
        Looks.Def d = medieval();
        looks.replace(Map.of(MEDIEVAL, d));
        Map<ResourceLocation, Map<String, ResourceLocation>> swaps = LookChoice.index(List.of(
            "towntopia:rustic: cook = myaddon:medieval",
            "towntopia:rustic: guard_1 = myaddon:medieval",
            "towntopia:rustic: farmer_female_1 = towntopia:london"), warnings::add);
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertTrue(LookChoice.anyTowntopiaInUse(swaps));
        assertFalse(LookChoice.anyTowntopiaInUse(LookChoice.index(List.of("myaddon:medieval: cook = towntopia:rustic"), warnings::add)));
        Map<String, ResourceLocation> rustic = swaps.get(ResourceLocation.parse("towntopia:rustic"));
        // the addon look's file for that skin
        assertEquals(new Looks.File(COOK_PNG, COOK_GEO), looks.pickWhileTowntopia(new Looks.SkinName("cook", false), rustic.get("cook")));
        // the look has no file for it, or the line names one of Towntopia's looks (Towntopia's own job): Towntopia draws
        assertNull(looks.pickWhileTowntopia(new Looks.SkinName("guard_1", false), rustic.get("guard_1")));
        assertNull(looks.pickWhileTowntopia(new Looks.SkinName("farmer_female_1", false), rustic.get("farmer_female_1")));
        assertNull(looks.pickWhileTowntopia(new Looks.SkinName("cook", false), null));
        assertNull(looks.pickWhileTowntopia(new Looks.SkinName("cook", false), ResourceLocation.parse("gone:pack")));
    }

    @Test
    void geoFolderMustBeUnderGeo() {
        // GeckoLib only bakes models under geo/ and throws for any other model path: such a look is refused
        assertTrue(looks.parse(MEDIEVAL, new StringReader("{\"name\": \"x\", \"geo_folder\": \"myaddon:models/medieval/\"}"), LooksTest::files).isEmpty());
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("geo/"), warnings.get(0));
        assertTrue(looks.parse(MEDIEVAL, new StringReader("{\"geo_folder\": \"myaddon:geo/medieval/\"}"), LooksTest::files).isPresent());
    }

    @Test
    void unknownLookInConfigFallsBackToTowntopiaChoice() {
        looks.replace(Map.of(MEDIEVAL, medieval()));
        ResourceLocation gone = ResourceLocation.parse("removed:pack");
        assertEquals(Optional.empty(), looks.current(gone));
        assertEquals(Optional.empty(), looks.current(gone));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("removed:pack"), warnings.get(0));
        // nothing chosen: Towntopia's choice, no warning
        assertEquals(Optional.empty(), looks.current(null));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(looks.current(MEDIEVAL).isPresent());
        // the pack is back after a reload: the saved choice works again
        looks.replace(Map.of(MEDIEVAL, medieval(), gone, looks.parse(gone, new StringReader("{}"), f -> List.of()).orElseThrow()));
        assertTrue(looks.current(gone).isPresent());
    }

    @Test
    void lookHookKeysExist() {
        Set<String> keys = HookManifest.get().byKey.keySet();
        for (String key : LookHooks.KEYS) assertTrue(keys.contains(key), key);
        assertFalse(LookHooks.KEYS.isEmpty());
    }
}
