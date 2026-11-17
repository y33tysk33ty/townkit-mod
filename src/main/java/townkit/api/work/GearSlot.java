package townkit.api.work;

/**
 * Where a piece of smith-made gear goes: one of Towntopia's tool kinds (axe, pickaxe, hoe, sword; the shovel too, which
 * Towntopia rates but never hands out) or an armour slot. Used by {@link ForgeRecipe.Builder#gear}.
 *
 * <p>Towntopia's smith makes axes, pickaxes, hoes and swords for the villagers who use them (lumberjacks, miners,
 * farmers, guards) and armour for its guards; a shovel recipe is made only when ordered (Mayor stock orders).
 *
 * @since 0.7.1
 */
public enum GearSlot {
    AXE,
    PICKAXE,
    HOE,
    SWORD,
    SHOVEL,
    HELMET,
    CHESTPLATE,
    LEGGINGS,
    BOOTS;

    /** True for the four armour slots. */
    public boolean isArmor() {
        return this.ordinal() >= HELMET.ordinal();
    }
}
