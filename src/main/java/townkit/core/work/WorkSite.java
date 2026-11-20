package townkit.core.work;

import javax.annotation.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/**
 * One instruction, in Towntopia's code, that the work overlay audits: a read of a list or recipe it replaces, a read of
 * a smith material's items, a call carrying a smith tier, a tier constant, or a return of a method it answers. Its key
 * ({@code owner#method#KIND}, or {@code owner#method#KIND@n} for the n-th constant of a kind in the method) names it in
 * {@code work-sites.tsv}. Must not load Minecraft classes (the mixin plugin uses it).
 *
 * @param owner  the class, relative to {@code com/alex/test/}
 * @param method name and descriptor, e.g. {@code isDish(Lnet/minecraft/world/item/ItemStack;)Z}
 */
public record WorkSite(String owner, String method, Kind kind) {
    private static final String P = "com/alex/test/";
    private static final String COOK_OWNER = P + "village/villagers/cook/behavior/CookActivity";
    private static final String COOK_RECIPE = COOK_OWNER + "$Recipe";
    private static final String SCHEMA_OWNER = P + "village/villagers/cook/settings/CookSettingsSchema";
    private static final String SMITH = P + "village/villagers/blacksmith/behavior/BlacksmithActivity";
    private static final String MAT = SMITH + "$Mat";
    private static final String RECIPE = SMITH + "$Recipe";
    private static final String PLANNER = SMITH + "$Planner";
    private static final String BOOK = P + "village/villagers/blacksmith/ForgeOrderBook";
    private static final String BALANCE = P + "village/villagers/blacksmith/BlacksmithBalance";
    private static final String LIST = "Ljava/util/List;";
    private static final String SET = "Ljava/util/Set;";
    private static final String ITEM = "Lnet/minecraft/world/item/Item;";
    private static final String RECIPE_DESC = "L" + RECIPE + ";";
    private static final String ITEMS = "net/minecraft/world/item/Items";
    private static final String CONTEXT = "Lcom/alex/test/village/brain/Activity$Context;";

    /** How a REWRITE row's instruction is pointed at its bridge. */
    public enum Style {
        /** {@code invokestatic bridge(T)T} right after the instruction (T: the value it pushes). */
        AFTER,
        /**
         * As {@link #AFTER} for a value of a type TownKit can't name (package-private in Towntopia): the bridge takes and
         * returns {@code Object}, and a {@code checkcast} to the type follows it.
         */
        AFTER_ERASED,
        /** {@code dup} before a {@code getfield}, {@code invokestatic bridge(Object owner, T)T} after it. */
        DUP_OWNER,
        /** {@code dup} before a one-argument call, {@code invokestatic bridge(A arg, R)R} after it. */
        DUP_ARG,
        /**
         * {@code dup} before a no-argument {@code invokevirtual} (an accessor), {@code invokestatic bridge(Object receiver, R)R}
         * after it.
         */
        DUP_RECEIVER,
        /**
         * Before every return of a static method: every argument is loaded again, then {@code invokestatic bridge(R, A...)R}
         * (R: the return type; A: the argument types, references as {@code Object}).
         */
        RETURN_ARGS,
        /** {@code dup2} before a static two-argument call, {@code invokestatic bridge(Object a, Object b, R)R} after it. */
        DUP2_ARGS,
        /** {@code BlacksmithActivity$Mat}'s {@code $values()} array goes through spare constants added to the enum. */
        ENUM_EXTEND,
        /** Never rewritten: listed so the audit sees every place the value flows (KEEP rows only). */
        AUDIT_ONLY
    }

    /** The audited instructions: every one in the jar is a row (REWRITE or KEEP), except {@linkplain #scoped scoped} kinds. */
    public enum Kind {
        /** {@code CookActivity.RECIPES}: the dish list. */
        RECIPES(Opcodes.GETSTATIC, COOK_OWNER, "RECIPES", LIST, Style.AFTER),
        /** {@code CookActivity.DISHES}: the dish results. */
        DISHES(Opcodes.GETSTATIC, COOK_OWNER, "DISHES", SET, Style.AFTER),
        /** {@code CookActivity.INGREDIENTS}: every dish ingredient. */
        INGREDIENTS(Opcodes.GETSTATIC, COOK_OWNER, "INGREDIENTS", SET, Style.AFTER),
        /** {@code CookActivity.RAW_FOR_COOK}: raw foods villagers leave for the cook (not part of the dish overlay). */
        RAW_FOR_COOK(Opcodes.GETSTATIC, COOK_OWNER, "RAW_FOR_COOK", SET, Style.AFTER),
        /**
         * {@code CookActivity$Recipe.inputsPerBatch()}: a dish's ingredients for one batch (a tag dish's swap to the
         * village's chosen variant).
         */
        INPUTS(Opcodes.INVOKEVIRTUAL, COOK_RECIPE, "inputsPerBatch", "()Ljava/util/Map;", Style.DUP_RECEIVER),
        /** {@code CookSettingsSchema.SCHEMA}: the cook's settings rows. */
        COOK_SCHEMA(Opcodes.GETSTATIC, SCHEMA_OWNER, "SCHEMA", "L" + P + "village/villagers/settings/SettingsSchema;", Style.AFTER),
        /** {@code CookSettingsSchema.DISHES}: the rows' own dish list (the schema is built from it at class init). */
        SCHEMA_DISHES(Opcodes.GETSTATIC, SCHEMA_OWNER, "DISHES", LIST, Style.AFTER),
        /** {@code BlacksmithActivity.SHIELD}: the smith's shield recipe. */
        SHIELD(Opcodes.GETSTATIC, SMITH, "SHIELD", RECIPE_DESC, Style.AFTER_ERASED),
        /** {@code BlacksmithActivity.SHEARS}: the smith's shears recipe. */
        SHEARS(Opcodes.GETSTATIC, SMITH, "SHEARS", RECIPE_DESC, Style.AFTER_ERASED),
        /** {@code BlacksmithActivity.BUCKET}: the smith's bucket recipe. */
        BUCKET(Opcodes.GETSTATIC, SMITH, "BUCKET", RECIPE_DESC, Style.AFTER_ERASED),
        /** {@code BlacksmithActivity.TEMPLATE_COPY}: the smith's netherite-template copy. */
        TEMPLATE_COPY(Opcodes.GETSTATIC, SMITH, "TEMPLATE_COPY", RECIPE_DESC, Style.AFTER_ERASED),
        /** {@code BlacksmithActivity$Mat.item}: a smith material's main item. */
        MAT_ITEM(Opcodes.GETFIELD, MAT, "item", ITEM, Style.DUP_OWNER),
        /** {@code BlacksmithActivity$Mat.items}: every item that counts as a smith material. */
        MAT_ITEMS(Opcodes.GETFIELD, MAT, "items", LIST, Style.DUP_OWNER),
        /** {@code BlacksmithActivity$Mat.$values()}: the enum's constants (spare materials are added to them). */
        MAT_VALUES(Opcodes.INVOKESTATIC, MAT, "$values", "()[L" + MAT + ";", Style.ENUM_EXTEND),
        /** {@code BlacksmithBalance.maxTier(int)}: the highest tier a smith of a level makes. */
        MAX_TIER(Opcodes.INVOKESTATIC, BALANCE, "maxTier", "(I)I", Style.DUP_ARG),
        /** {@code BlacksmithActivity$Recipe.tier()}: a smith recipe's tier. */
        RECIPE_TIER(Opcodes.INVOKEVIRTUAL, RECIPE, "tier", "()I", Style.AFTER),
        /** {@code ForgeOrderBook.tierOf(Item)}: an item's gear tier (its body is a RETURN row; callers are listed). */
        TIER_OF(Opcodes.INVOKESTATIC, BOOK, "tierOf", "(" + ITEM + ")I", Style.AUDIT_ONLY),
        /** {@code BlacksmithActivity$Planner.maxTier}: the highest tier this plan makes. */
        PLANNER_MAX_TIER(Opcodes.GETFIELD, PLANNER, "maxTier", "I", Style.AUDIT_ONLY),
        /** {@code BlacksmithActivity$Planner.orderTier}: the highest tier this plan makes for orders. */
        PLANNER_ORDER_TIER(Opcodes.GETFIELD, PLANNER, "orderTier", "I", Style.AUDIT_ONLY),
        /** {@code BlacksmithActivity.SPARE_ORE}: the pairs of raw and ingot the smith's away catch-up smelts. */
        SPARE_ORE(Opcodes.GETSTATIC, SMITH, "SPARE_ORE", "[[" + ITEM, Style.AFTER),
        /**
         * {@code BlacksmithActivity.countItem(ctx, item)}: how many of an item the smith carries (in the methods rows name).
         */
        COUNT_ITEM(Opcodes.INVOKESTATIC, SMITH, "countItem", "(" + CONTEXT + ITEM + ")I", Style.DUP2_ARGS, true),
        /** {@code Items.RAW_COPPER}, where the smith's spare-ore smelting names it (in the methods rows name). */
        ITEM_RAW_COPPER(Opcodes.GETSTATIC, ITEMS, "RAW_COPPER", ITEM, Style.AFTER, true),
        /** {@code Items.IRON_INGOT}, where the smith's smelting step makes it (in the methods rows name). */
        ITEM_IRON_INGOT(Opcodes.GETSTATIC, ITEMS, "IRON_INGOT", ITEM, Style.AFTER, true),
        /** {@code Items.GOLD_INGOT}, where the smith's smelting step makes it (in the methods rows name). */
        ITEM_GOLD_INGOT(Opcodes.GETSTATIC, ITEMS, "GOLD_INGOT", ITEM, Style.AFTER, true),
        /** {@code Items.COPPER_INGOT}, where the smith's smelting step makes it (in the methods rows name). */
        ITEM_COPPER_INGOT(Opcodes.GETSTATIC, ITEMS, "COPPER_INGOT", ITEM, Style.AFTER, true),
        /**
         * {@code Set.isEmpty()} (in the methods rows name): Towntopia sorts the mines by the villager's wanted ores only
         * when that set is not empty.
         */
        SET_IS_EMPTY(Opcodes.INVOKEINTERFACE, "java/util/Set", "isEmpty", "()Z", Style.AFTER, true),
        /** {@code iconst_1} in a scoped method (rows name each occurrence). */
        INT_1(Opcodes.ICONST_1),
        /** {@code iconst_2} in a scoped method. */
        INT_2(Opcodes.ICONST_2),
        /** {@code iconst_3} in a scoped method. */
        INT_3(Opcodes.ICONST_3),
        /** {@code iconst_4} in a scoped method. */
        INT_4(Opcodes.ICONST_4),
        /** {@code iconst_5} in a scoped method. */
        INT_5(Opcodes.ICONST_5),
        /** Every {@code ireturn} of a scoped method. */
        IRETURN(Opcodes.IRETURN),
        /** Every {@code areturn} of a scoped method. */
        ARETURN(Opcodes.ARETURN);

        public final int opcode;
        /** Field or method owner (internal name); null for scoped kinds. */
        @Nullable
        public final String insnOwner;
        @Nullable
        public final String name;
        /** Field descriptor or method descriptor; null for scoped kinds. */
        @Nullable
        public final String desc;
        public final Style style;
        /**
         * Scoped kinds (constants and returns) are audited only in the methods rows name: they are everywhere in the jar.
         * Constants are listed one row per occurrence ({@code KIND@n}); returns one row per method.
         */
        public final boolean scoped;

        Kind(int opcode, String owner, String name, String desc, Style style) {
            this(opcode, owner, name, desc, style, false);
        }

        /** A field read or call that is audited only in the methods rows name (it is everywhere in the jar). */
        Kind(int opcode, String owner, String name, String desc, Style style, boolean scoped) {
            this.opcode = opcode;
            this.insnOwner = owner;
            this.name = name;
            this.desc = desc;
            this.style = style;
            this.scoped = scoped;
        }

        Kind(int opcode) {
            this.opcode = opcode;
            this.insnOwner = null;
            this.name = null;
            this.desc = null;
            this.style = opcode == Opcodes.IRETURN || opcode == Opcodes.ARETURN ? Style.RETURN_ARGS : Style.AFTER;
            this.scoped = true;
        }

        /** Whether rows of this kind name one occurrence each ({@code KIND@n}). */
        public boolean perOccurrence() {
            return this.scoped && this.style != Style.RETURN_ARGS;
        }

        /** The kind of {@code in}, or null when it is none of the audited instructions. Scoped kinds match anywhere. */
        @Nullable
        public static Kind of(AbstractInsnNode in) {
            int op = in.getOpcode();
            for (Kind k : values()) {
                if (k.opcode != op) continue;
                if (k.scoped && k.insnOwner == null) return k;
                if (in instanceof FieldInsnNode f && k.insnOwner.equals(f.owner) && k.name.equals(f.name) && k.desc.equals(f.desc)) return k;
                if (in instanceof MethodInsnNode m && k.insnOwner.equals(m.owner) && k.name.equals(m.name) && k.desc.equals(m.desc)) return k;
            }
            return null;
        }

        /**
         * The descriptor of the bridge a REWRITE row of this kind calls, for an instruction in a method with descriptor
         * {@code methodDesc} (returns use its return type).
         */
        public String bridgeDesc(String methodDesc) {
            return switch (this.style) {
                case AFTER -> {
                    String t = pushed();
                    yield "(" + t + ")" + t;
                }
                case AFTER_ERASED -> "(Ljava/lang/Object;)Ljava/lang/Object;";
                case DUP_OWNER -> "(Ljava/lang/Object;" + this.desc + ")" + this.desc;
                case DUP_RECEIVER -> {
                    String r = Type.getMethodType(this.desc).getReturnType().getDescriptor();
                    yield "(Ljava/lang/Object;" + r + ")" + r;
                }
                case DUP_ARG -> {
                    Type m = Type.getMethodType(this.desc);
                    yield "(" + m.getArgumentTypes()[0].getDescriptor() + m.getReturnType().getDescriptor() + ")" + m.getReturnType().getDescriptor();
                }
                case RETURN_ARGS -> {
                    Type m = Type.getMethodType(methodDesc);
                    yield "(" + m.getReturnType().getDescriptor() + erased(m) + ")" + m.getReturnType().getDescriptor();
                }
                case DUP2_ARGS -> {
                    Type m = Type.getMethodType(this.desc);
                    yield "(" + erased(m) + m.getReturnType().getDescriptor() + ")" + m.getReturnType().getDescriptor();
                }
                case ENUM_EXTEND, AUDIT_ONLY -> "";
            };
        }

        /** The argument descriptors of {@code m}, references as {@code Object}. */
        private static String erased(Type m) {
            StringBuilder sb = new StringBuilder();
            for (Type a : m.getArgumentTypes()) {
                sb.append(a.getSort() == Type.OBJECT || a.getSort() == Type.ARRAY ? "Ljava/lang/Object;" : a.getDescriptor());
            }
            return sb.toString();
        }

        /** The type the instruction leaves on the stack. */
        private String pushed() {
            if (this.scoped && this.insnOwner == null) return "I";
            if (this.opcode == Opcodes.GETSTATIC || this.opcode == Opcodes.GETFIELD) return this.desc;
            return Type.getMethodType(this.desc).getReturnType().getDescriptor();
        }
    }

    public String key() {
        return this.owner + "#" + this.method + "#" + this.kind.name();
    }
}
