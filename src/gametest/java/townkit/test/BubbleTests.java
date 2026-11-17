package townkit.test;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.social.ThoughtIcon;
import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import townkit.api.bubble.BubbleIcon;
import townkit.api.bubble.BubbleKind;
import townkit.api.bubble.TownKitBubbles;
import townkit.api.bubble.Valence;
import townkit.core.TownKit;
import townkit.core.bubble.BubbleHooks;
import townkit.core.bubble.CoreBubbles;
import townkit.core.look.LookSync;
import townkit.core.look.VillagerLook;
import townkit.test.BehaviourTests.TestVillage;

/**
 * Contract tests for {@link TownKitBubbles} in real Towntopia villages: each test that needs a villager uses the farmer
 * of its own test village (brain suspended; no real player is near, so Towntopia shows no bubbles of its own). Each test
 * has its own batch, so the countdown and hooks it checks are its own, and leaves nothing live.
 */
@GameTestHolder(TownKit.MODID)
@PrefixGameTestTemplate(false)
public final class BubbleTests {
    private static final ResourceLocation COIN = ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "test_coin_bubble");

    private BubbleTests() {
    }

    /**
     * Runs {@code body} with the farmer of a new test village at ({@code x}, {@code z}), brain suspended. Each test has
     * its own village: between this class's batches other tests clear the simulated player, a shared village goes
     * dormant, and Towntopia takes its villagers' bodies out of the level.
     */
    private static void withVillager(GameTestHelper helper, int x, int z, Consumer<NpcEntity> body) {
        TestVillage tv = BehaviourTests.village(helper, x, z).orElse(null);
        if (tv == null) return;
        VillagerModel model = tv.mgr().getModel(tv.farmer().getNpcId());
        if (model != null) model.setBrainSuspended(true);
        body.accept(tv.farmer());
    }

    /** The icon network id in the NPC's synced bubble value (0 for none). */
    static int packedIcon(NpcEntity npc) {
        try {
            Field f = NpcEntity.class.getDeclaredField("BUBBLE");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            EntityDataAccessor<Integer> bubble = (EntityDataAccessor<Integer>) f.get(null);
            return npc.getEntityData().get(bubble) & 127;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("can't read NpcEntity.BUBBLE", e);
        }
    }

    private static BubbleIcon coin() {
        return TownKitBubbles.icon(COIN, Valence.POSITIVE);
    }

    private static void cleanUp(Checks c, NpcEntity npc) {
        TownKitBubbles.clear(npc);
        c.check("no bubble left", !npc.hasBubble() && LookSync.look(npc).isEmpty(), LookSync.look(npc));
        c.check("nothing live, no tick listener, no hooks", CoreBubbles.liveCount() == 0 && !CoreBubbles.ticking()
            && BubbleHooks.attachedKeys().isEmpty(), CoreBubbles.liveCount() + " live, " + BubbleHooks.attachedKeys());
    }

    /** Icons: Towntopia's by id with its valence, the built-in list, addon registration and its checks. */
    @GameTest(template = "empty", batch = "townkit_bubbles_icons")
    public static void icons(GameTestHelper helper) {
        Checks c = new Checks("icons");
        BubbleIcon hungry = BubbleIcon.towntopia("status_hungry");
        c.check("Towntopia icon id and valence", hungry.equals(new BubbleIcon(ResourceLocation.parse("towntopia:status_hungry"), Valence.NEGATIVE)), hungry);
        List<BubbleIcon> builtIns = TownKitBubbles.builtIns();
        c.check("builtIns: every Towntopia icon, in Towntopia's order", builtIns.size() == ThoughtIcon.values().length
            && builtIns.get(0).equals(hungry) && builtIns.contains(new BubbleIcon(ResourceLocation.parse("towntopia:emote_love"), Valence.POSITIVE)),
            builtIns.size());
        c.check("unknown Towntopia icon refused", threw(() -> BubbleIcon.towntopia("status_bogus")), "status_bogus");
        c.check("addon icon registered", coin().equals(new BubbleIcon(COIN, Valence.POSITIVE)), coin());
        c.check("same id, other valence refused", threw(() -> TownKitBubbles.icon(COIN, Valence.NEGATIVE)), COIN);
        c.check("towntopia namespace refused for addon icons",
            threw(() -> TownKitBubbles.icon(ResourceLocation.parse("towntopia:status_hungry"), Valence.NEGATIVE)), "towntopia:");
        c.finish(helper);
    }

    /** A Towntopia icon goes through Towntopia's own bubble: the NPC's synced value holds it. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_bubbles_towntopia")
    public static void showTowntopiaIcon(GameTestHelper helper) {
        Checks c = new Checks("showTowntopiaIcon");
        withVillager(helper, 14400, 8000, npc -> {
            boolean shown = TownKitBubbles.show(npc, BubbleIcon.towntopia("status_hungry"), BubbleKind.THOUGHT, 100);
            c.check("shown", shown, shown);
            c.check("entity data packed icon equals status_hungry", packedIcon(npc) == ThoughtIcon.STATUS_HUNGRY.networkId(), packedIcon(npc));
            c.check("no addon look", LookSync.look(npc).isEmpty(), LookSync.look(npc));
            c.check("bad ticks refused", threw(() -> TownKitBubbles.show(npc, BubbleIcon.towntopia("status_hungry"), BubbleKind.THOUGHT, 0)), 0);
            cleanUp(c, npc);
            BehaviourTests.finish(helper, c);
        });
    }

    /**
     * An addon icon goes through TownKit's look, with Towntopia's bubble cleared; a later Towntopia bubble replaces it,
     * and showing it again then clearing it leaves nothing.
     */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_bubbles_addon")
    public static void showAddonIcon(GameTestHelper helper) {
        Checks c = new Checks("showAddonIcon");
        withVillager(helper, 14800, 8000, npc -> {
            npc.showBubble(ThoughtIcon.EMOTE_TALKING, false, 200);
            boolean shown = TownKitBubbles.show(npc, coin(), BubbleKind.SPEECH, 100, true);
            c.check("shown", shown, shown);
            c.check("LookSync has the id as a speech bubble", VillagerLook.EMPTY.withBubble(COIN, true, 100).equals(LookSync.look(npc)), LookSync.look(npc));
            c.check("Towntopia bubble cleared", !npc.hasBubble(), packedIcon(npc));
            c.check("live, ticking, show hook attached", CoreBubbles.liveCount() == 1 && CoreBubbles.ticking()
                && !BubbleHooks.attachedKeys().isEmpty(), CoreBubbles.liveCount() + " live, " + BubbleHooks.attachedKeys());
            npc.showBubble(ThoughtIcon.EMOTE_TALKING, false, 200);
            c.checkHooksOn("a Towntopia bubble replaces the addon one", npc.hasBubble() && LookSync.look(npc).isEmpty(), LookSync.look(npc));
            c.checkHooksOn("nothing live after the replacement", CoreBubbles.liveCount() == 0, CoreBubbles.liveCount());
            TownKitBubbles.show(npc, coin(), BubbleKind.THOUGHT, 100);
            TownKitBubbles.clear(npc);
            c.check("clear drops the addon bubble", LookSync.look(npc).isEmpty(), LookSync.look(npc));
            c.check("unregistered addon icon refused", threw(() -> TownKitBubbles.show(npc,
                new BubbleIcon(ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "never_registered"), Valence.NEUTRAL), BubbleKind.THOUGHT, 20)), "never_registered");
            cleanUp(c, npc);
            BehaviourTests.finish(helper, c);
        });
    }

    /** A vanilla villager is no Towntopia NPC; a call off the server thread throws. */
    @GameTest(template = "empty", batch = "townkit_bubbles_non_npc")
    public static void showOnNonNpcReturnsFalse(GameTestHelper helper) {
        Checks c = new Checks("showOnNonNpcReturnsFalse");
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 2, 1);
        try {
            c.check("addon icon on a vanilla villager: false", !TownKitBubbles.show(villager, coin(), BubbleKind.THOUGHT, 40), villager);
            c.check("Towntopia icon on a vanilla villager: false",
                !TownKitBubbles.show(villager, BubbleIcon.towntopia("status_hungry"), BubbleKind.THOUGHT, 40), villager);
            c.check("no look stored", LookSync.look(villager).isEmpty() && CoreBubbles.liveCount() == 0, LookSync.look(villager));
            TownKitBubbles.clear(villager);
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            Thread t = new Thread(() -> {
                try {
                    TownKitBubbles.show(villager, coin(), BubbleKind.THOUGHT, 40);
                } catch (Throwable e) {
                    thrown.set(e);
                }
            }, "townkit-bubble-test");
            t.start();
            t.join(5000);
            c.check("off the server thread: IllegalStateException", thrown.get() instanceof IllegalStateException, thrown.get());
        } catch (InterruptedException e) {
            c.check("not interrupted", false, e);
        } finally {
            villager.discard();
        }
        c.finish(helper);
    }

    /** After {@code ticks} the server drops the addon bubble: the look goes back to EMPTY and the countdown stops. */
    @GameTest(template = "empty", timeoutTicks = 400, batch = "townkit_bubbles_expire")
    public static void bubbleExpires(GameTestHelper helper) {
        Checks c = new Checks("bubbleExpires");
        withVillager(helper, 15200, 8000, npc -> {
            TownKitBubbles.show(npc, coin(), BubbleKind.THOUGHT, 20, true);
            helper.runAfterDelay(10, () -> c.check("still showing half way", COIN.equals(LookSync.look(npc).bubble()), LookSync.look(npc)));
            helper.runAfterDelay(25, () -> {
                c.check("after ticks the look's bubble is null", LookSync.look(npc).bubble() == null, LookSync.look(npc));
                c.check("look back to EMPTY", LookSync.look(npc).isEmpty(), LookSync.look(npc));
                cleanUp(c, npc);
                BehaviourTests.finish(helper, c);
            });
        });
    }

    private static boolean threw(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }
}
