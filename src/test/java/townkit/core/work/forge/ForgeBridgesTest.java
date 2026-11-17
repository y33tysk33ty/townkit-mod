package townkit.core.work.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import townkit.api.work.GearSlot;

/** The smith's ladder answers that need no game: the highest tier a smith may forge, and addon gear's rating. */
class ForgeBridgesTest {
    private static final int DIAMOND_LEVEL = 40;
    private static final int NETHERITE_LEVEL = 70;

    private static ForgeOverlay.Rung own(String path, int tier, int skill) {
        return new ForgeOverlay.Rung(ResourceLocation.fromNamespaceAndPath("towntopia", path), tier, tier, skill);
    }

    private static ForgeOverlay.Rung addon(String path, int above, int skill) {
        return new ForgeOverlay.Rung(ResourceLocation.fromNamespaceAndPath("mymod", path), -1, above, skill);
    }

    /** wood, stone, iron, [addon], diamond, [addon], netherite. */
    private static List<ForgeOverlay.Rung> ladder(ForgeOverlay.Rung belowDiamond, ForgeOverlay.Rung aboveDiamond) {
        return List.of(own("wood", 0, 1), own("stone", 1, 1), own("iron", 2, 1), belowDiamond, own("diamond", 3, DIAMOND_LEVEL),
            aboveDiamond, own("netherite", 4, NETHERITE_LEVEL));
    }

    /** A low-skill addon tier above diamond doesn't unlock diamond for a smith below its level. */
    @Test
    void addonTierAboveADisallowedTowntopiaTierDoesNotUnlockIt() {
        List<ForgeOverlay.Rung> l = ladder(addon("bronze", 3, DIAMOND_LEVEL), addon("steel", 4, 5));
        // a level 5 smith: Towntopia allows up to iron (2); steel's skill 5 must not lift it past diamond
        assertEquals(2, ForgeBridges.maxTier(l, 5, 2), "iron's position");
    }

    @Test
    void addonTierBelowTheNextTowntopiaTierCountsByItsSkill() {
        List<ForgeOverlay.Rung> l = ladder(addon("bronze", 3, 30), addon("steel", 4, NETHERITE_LEVEL));
        assertEquals(3, ForgeBridges.maxTier(l, 30, 2), "bronze (skill 30) above iron, diamond not yet");
        assertEquals(2, ForgeBridges.maxTier(l, 29, 2), "below bronze's skill: iron");
    }

    @Test
    void addonTierTheLevelDoesNotAllowIsSteppedOver() {
        List<ForgeOverlay.Rung> l = ladder(addon("bronze", 3, 50), addon("steel", 4, NETHERITE_LEVEL));
        assertEquals(4, ForgeBridges.maxTier(l, 45, 3), "diamond allowed by Towntopia, bronze (50) skipped");
        assertEquals(6, ForgeBridges.maxTier(l, NETHERITE_LEVEL, 4), "everything");
        assertEquals(0, ForgeBridges.maxTier(l, 0, 0), "wood only");
    }

    private static ForgeHooks.Gear gear(int over) {
        return new ForgeHooks.Gear(GearSlot.SWORD, null, 0, 0f, 0, null, null, 0.5, over);
    }

    /** With no room between the neighbours, addon gear rates as the floor, never at or above the ceiling. */
    @Test
    void gearRatingStaysBelowTheCeiling() {
        assertEquals(10L, gear(0).between(10L, 11L), "one apart");
        assertEquals(10L, gear(0).between(10L, 10L), "equal");
        long mid = gear(0).between(10L, 20L);
        assertTrue(mid > 10L && mid < 20L, String.valueOf(mid));
        assertEquals(1010L, gear(1).between(10L, 11L), "past netherite: above it");
        assertEquals(1.0, gear(0).between(1.0, 1.0), 0.0);
        assertEquals(1.5, gear(0).between(1.0, 2.0), 1e-9);
    }
}
