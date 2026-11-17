package townkit.core.village;

import java.util.Locale;
import net.minecraft.resources.ResourceLocation;
import townkit.core.BuildInfo;

/** Stable ids for Towntopia's hard-coded kinds (professions, building classes), so the API never hands out Towntopia's enums or classes. */
public final class Ids {
    private Ids() {
    }

    /** {@code FARMER} → {@code towntopia:farmer}. Works for professions Towntopia adds later too. */
    public static ResourceLocation profession(String enumName) {
        return ResourceLocation.fromNamespaceAndPath(BuildInfo.TOWNTOPIA_MODID, enumName.toLowerCase(Locale.ROOT));
    }

    /** {@code KitchenBuilding} → {@code towntopia:kitchen}, {@code MerchantStallBuilding} → {@code towntopia:merchant_stall}. */
    public static ResourceLocation building(Class<?> buildingClass) {
        String name = buildingClass.getSimpleName();
        if (name.endsWith("Building") && name.length() > "Building".length()) name = name.substring(0, name.length() - "Building".length());
        String snake = name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        return ResourceLocation.fromNamespaceAndPath(BuildInfo.TOWNTOPIA_MODID, snake);
    }
}
