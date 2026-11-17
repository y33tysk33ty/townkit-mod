package townkit.core.building;

/**
 * One place in Towntopia's code that tests a block: a {@code Blocks.X} compared through {@code BlockState.is},
 * an {@code instanceof} on a block class, or a {@code Blocks.X} reference compared with {@code ==}.
 *
 * @param owner  internal class name relative to {@code com/alex/test/}, e.g. {@code village/building/KitchenBuilding}
 * @param method method name plus descriptor
 * @param kind   what kind of check
 * @param target the {@code Blocks} field name (BLOCK_IS, BLOCK_EQUALS) or the internal block class name (INSTANCEOF)
 */
public record CheckSite(String owner, String method, Kind kind, String target) {
    public enum Kind { BLOCK_IS, INSTANCEOF, BLOCK_EQUALS }

    /** Stable identity: {@code owner#method#kind#target}. */
    public String key() {
        return owner + "#" + method + "#" + kind + "#" + target;
    }
}
