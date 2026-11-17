package townkit.api.village;

/**
 * What kind of thing an item is, as Towntopia sorts village storage. Same names and order as Towntopia's own
 * categories; {@link #MISC} is everything else.
 *
 * @since 0.6.2
 */
public enum StorageCategory {
    FOOD,
    CROP,
    SEEDS,
    RAW_MEAT,
    WOOD,
    STONE,
    ORES,
    TOOLS,
    ARMOR,
    ENCHANTS,
    MISC
}
