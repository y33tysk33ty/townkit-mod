package townkit.test;

import com.alex.test.entity.NpcEntity;
import com.alex.test.testkit.TestKit;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.ThoughtState;
import com.alex.test.village.brain.VillagerBrain;
import com.alex.test.village.social.ThoughtBubbles;
import com.alex.test.village.social.ThoughtIcon;
import com.mojang.authlib.GameProfile;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import townkit.api.Subscription;
import townkit.api.bubble.BubbleIcon;
import townkit.api.bubble.BubbleKind;
import townkit.api.bubble.BubbleState;
import townkit.api.bubble.TownKitBubbles;
import townkit.api.bubble.Valence;
import townkit.api.event.TownKitEvents;
import townkit.api.village.Villager;
import townkit.core.TownKit;
import townkit.core.bubble.BubbleHooks;
import townkit.core.bubble.BubbleStates;
import townkit.core.bubble.CoreBubbles;
import townkit.core.look.LookSync;
import townkit.core.village.VillagerHandle;
import townkit.test.BehaviourTests.TestVillage;

/**
 * Contract tests for addon states, hides, swaps, idle thoughts and {@code BUBBLE_SHOWING}, in one shared test village
 * (a farmer with its brain suspended, so Towntopia shows nothing on its own). Towntopia only shows bubbles with a
 * player within 20 blocks, so each test puts a fake player (added to the level, so the level counts it) next to the
 * farmer and takes it out after. Each test has its own batch and removes everything it registered.
 */
@GameTestHolder(TownKit.MODID)
@PrefixGameTestTemplate(false)
public final class BubbleStateTests {
    private static final ResourceLocation COIN = ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "test_coin_bubble");
    private static final String OWNER = "townkit-test";
    /** One check round (40 ticks), plus the tick the state was registered in. */
    private static final int CHECK_ROUND = 41;
    private static final int VILLAGE_X = 15600;
    private static final int VILLAGE_Z = 8000;
    /** How long a test waits for the farmer's body to come back with its chunk. */
    private static final int FARMER_WAIT = 100;

    @Nullable
    private static TestVillage shared;

    private BubbleStateTests() {
    }

    /** One test's villager, fake player and registrations. */
    private record Scene(GameTestHelper helper, Checks c, NpcEntity npc, FakePlayer player, List<Subscription> subs) {
        ServerLevel level() {
            return this.helper.getLevel();
        }

        Villager villager() {
            return VillagerHandle.of(this.npc);
        }

        /**
         * Removes what the test registered, the bubble and the player. Safe to run more than once: {@link #end} runs it,
         * and so does the test's end listener (see {@link #scene}), for a test that failed or timed out before its end.
         */
        void cleanUp() {
            this.subs.forEach(Subscription::remove);
            if (!this.npc.isRemoved()) TownKitBubbles.clear(this.npc);
            if (!this.player.isRemoved()) this.level().removePlayerImmediately(this.player, Entity.RemovalReason.DISCARDED);
        }

        /** Cleans up; checks nothing is left; finishes. */
        void end() {
            cleanUp();
            this.c.check("nothing left: no bubble, no hooks, no tick listeners", !this.npc.hasBubble() && LookSync.look(this.npc).isEmpty()
                && BubbleHooks.attachedKeys().isEmpty() && !BubbleStates.ticking() && !CoreBubbles.ticking(),
                BubbleHooks.attachedKeys() + ", states ticking " + BubbleStates.ticking());
            BehaviourTests.finish(this.helper, this.c);
        }
    }

    private static void scene(GameTestHelper helper, String name, Consumer<Scene> body) {
        Checks c = new Checks(name);
        TestVillage tv = village(helper).orElse(null);
        if (tv == null) return;
        // whatever way the test ends (passed, a failed check, an exception, a timeout), what it registered goes: a state,
        // rule or listener left behind would change Towntopia's bubbles and hooks for every later test
        AtomicReference<Scene> open = new AtomicReference<>();
        helper.testInfo.addListener(new GameTestListener() {
            @Override
            public void testStructureLoaded(GameTestInfo info) {
            }

            @Override
            public void testPassed(GameTestInfo info, GameTestRunner runner) {
                cleanUp();
            }

            @Override
            public void testFailed(GameTestInfo info, GameTestRunner runner) {
                cleanUp();
            }

            @Override
            public void testAddedForRerun(GameTestInfo old, GameTestInfo fresh, GameTestRunner runner) {
            }

            private void cleanUp() {
                Scene s = open.getAndSet(null);
                if (s != null) s.cleanUp();
            }
        });
        whenFarmerLoaded(helper, c, FARMER_WAIT, farmer -> {
            VillagerModel model = shared.mgr().getModel(farmer.getNpcId());
            if (model != null) model.setBrainSuspended(true);
            ServerLevel level = helper.getLevel();
            TownKitBubbles.clear(farmer);
            FakePlayer player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "townkit_bubble_test"));
            player.setPos(farmer.getX() + 2, farmer.getY(), farmer.getZ());
            level.addNewPlayer(player);
            Scene s = new Scene(helper, c, farmer, player, new ArrayList<>());
            open.set(s);
            try {
                body.accept(s);
            } catch (GameTestAssertException e) {
                throw e; // a failed check, already reported and cleaned up by end()
            } catch (RuntimeException e) {
                TownKit.LOG.error("[TownKit test] {} threw", name, e);
                c.check("no exception", false, e);
                s.end();
            }
        });
    }

    /**
     * Runs {@code then} with this class's shared test village's farmer (brain suspended) once its body is in the level.
     * Other test classes reuse the village this way ({@code AnimationTests}), so no new test village is built for them.
     */
    static void withSharedFarmer(GameTestHelper helper, Checks c, Consumer<NpcEntity> then) {
        if (village(helper).isEmpty()) return;
        whenFarmerLoaded(helper, c, FARMER_WAIT, farmer -> {
            VillagerModel model = shared.mgr().getModel(farmer.getNpcId());
            if (model != null) model.setBrainSuspended(true);
            then.accept(farmer);
        });
    }

    /**
     * Runs {@code then} with the shared village's farmer once its body is in the level (the level knows it by its id).
     * Between batches the GameTest runner un-forces every forced chunk, so the village's chunks unload, the farmer's
     * body goes with them and the village goes dormant; {@link #village} forces the chunks again, and the body comes
     * back with its chunk, as a new entity. Checked each tick for up to {@code ticks} ticks.
     */
    private static void whenFarmerLoaded(GameTestHelper helper, Checks c, int ticks, Consumer<NpcEntity> then) {
        ServerLevel level = helper.getLevel();
        VillagerModel model = shared.mgr().getModel(shared.farmer().getNpcId());
        NpcEntity body = model == null ? null : model.puppet();
        if (body != null && !body.isRemoved() && level.getEntity(body.getId()) == body) {
            if (body != shared.farmer()) shared = new TestVillage(shared.mgr(), shared.door(), body);
            then.accept(body);
            return;
        }
        if (ticks <= 0) {
            c.check("the test farmer is in the level", false, model == null ? "no model" : "body " + body);
            BehaviourTests.finish(helper, c);
            return;
        }
        LoadWait.notYet();
        helper.runAfterDelay(1, () -> whenFarmerLoaded(helper, c, ticks - 1, then));
    }

    /**
     * The shared test village (made by the first test that runs), its chunks forced and the village awake: the runner
     * un-forced the chunks after the last batch.
     */
    private static Optional<TestVillage> village(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TestVillage tv = shared;
        VillageManagerRegistry reg = VillageManagerRegistry.get(level.getServer());
        if (tv != null && reg.get(tv.mgr().getVillageId()) == tv.mgr()) {
            BehaviourTests.forceChunks(level, VILLAGE_X, VILLAGE_Z);
            TestKit.setHeadless(true);
            TestKit.setSimulatedPlayer(tv.door(), level.dimension());
            UUID id = tv.mgr().getVillageId();
            for (int i = 0; i < 2 && reg.isVillageDormantOrReconciling(id); i++) reg.debugSetDormant(level, id, false);
            return Optional.of(tv);
        }
        Optional<TestVillage> v = BehaviourTests.village(helper, VILLAGE_X, VILLAGE_Z);
        v.ifPresent(t -> shared = t);
        return v;
    }

    private static BubbleIcon coin() {
        return TownKitBubbles.icon(COIN, Valence.POSITIVE);
    }

    private static BubbleIcon hungry() {
        return BubbleIcon.towntopia("status_hungry");
    }

    private static boolean showsCoin(NpcEntity npc) {
        return COIN.equals(LookSync.look(npc).bubble());
    }

    // --- Towntopia's ThoughtBubbles of a villager, driven by hand ----------------------------------------------------

    private static Object field(Object owner, Class<?> type, String name) {
        try {
            Field f = type.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(owner);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("can't read " + type.getSimpleName() + "." + name, e);
        }
    }

    private static void set(Object owner, String name, Object value) {
        try {
            Field f = ThoughtBubbles.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(owner, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("can't set ThoughtBubbles." + name, e);
        }
    }

    private static VillagerBrain brain(Scene s) {
        VillagerModel model = shared.mgr().getModel(s.npc().getNpcId());
        if (model == null) throw new IllegalStateException("no model for the test farmer");
        return model.brain();
    }

    /**
     * Towntopia's {@code onState} for the farmer with {@code state}, as its brain calls it, from a clean slate (last
     * state "fine", no timers running), so a state that isn't "fine" shows its icon, and "fine" an idle thought.
     */
    private static void towntopiaState(Scene s, ThoughtState state) {
        VillagerBrain brain = brain(s);
        ThoughtBubbles bubbles = (ThoughtBubbles) field(brain, VillagerBrain.class, "bubbles");
        set(bubbles, "last", ThoughtState.OK);
        set(bubbles, "repeatTimer", 0);
        set(bubbles, "idleTimer", 0);
        set(bubbles, "holdTimer", 0);
        bubbles.onState(s.npc(), s.level(), brain.memory(), state, 100.0F, false);
    }

    /** How many villager "huh"s {@code r} plays at {@code npc}. */
    private static int huhs(NpcEntity npc, Runnable r) {
        AtomicInteger n = new AtomicInteger();
        Consumer<PlayLevelSoundEvent.AtPosition> listener = e -> {
            if (e.getSound() != null && e.getSound().value() == SoundEvents.VILLAGER_AMBIENT && e.getPosition().distanceToSqr(npc.position()) < 1.0E-4) {
                n.incrementAndGet();
            }
        };
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            r.run();
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
        }
        return n.get();
    }

    // --- tests -----------------------------------------------------------------------------------------------------

    /** With nothing registered no bubble hook is attached and no tick listener runs; each registration attaches its own. */
    @GameTest(template = "empty", batch = "townkit_bubble_states_nohooks")
    public static void noRegistrationsNoHooks(GameTestHelper helper) {
        Checks c = new Checks("noRegistrationsNoHooks");
        List<Subscription> subs = new ArrayList<>();
        try {
            noRegistrationsNoHooks(c, subs);
        } finally {
            subs.forEach(Subscription::remove); // removed already unless a check threw: then nothing is left behind
        }
        c.finish(helper);
    }

    private static void noRegistrationsNoHooks(Checks c, List<Subscription> subs) {
        c.check("nothing attached, nothing ticking", BubbleHooks.attachedKeys().isEmpty() && !BubbleStates.ticking() && !CoreBubbles.ticking(),
            BubbleHooks.attachedKeys());
        Subscription state = track(subs, TownKitBubbles.state(OWNER, BubbleState.builder(ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "test_never"),
            coin(), v -> false).build()));
        c.check("a state: onState hook and the states' ticker", BubbleHooks.attachedKeys().equals(List.of("before " + onStateKey()))
            && BubbleStates.ticking(), BubbleHooks.attachedKeys());
        state.remove();
        c.check("state gone: nothing", BubbleHooks.attachedKeys().isEmpty() && !BubbleStates.ticking(), BubbleHooks.attachedKeys());
        Subscription hide = track(subs, TownKitBubbles.hide(OWNER, hungry()));
        c.check("a hide: show and huh hooks only", BubbleHooks.attachedKeys().size() == 2 && !BubbleStates.ticking(), BubbleHooks.attachedKeys());
        hide.remove();
        hide.remove();
        c.check("hide gone (removed twice): nothing", BubbleHooks.attachedKeys().isEmpty(), BubbleHooks.attachedKeys());
        Subscription idle = track(subs, TownKitBubbles.addIdleThought(OWNER, coin(), 1));
        c.check("an idle thought: show, huh, onState and idleThought hooks", BubbleHooks.attachedKeys().size() == 4 && !BubbleStates.ticking(),
            BubbleHooks.attachedKeys());
        idle.remove();
        Subscription listen = track(subs, TownKitEvents.BUBBLE_SHOWING.listen(OWNER, e -> { }));
        c.check("BUBBLE_SHOWING is available exactly when hooks are on", TownKitEvents.BUBBLE_SHOWING.available() == !HooksOff.off(), HooksOff.off());
        c.checkHooksOn("a BUBBLE_SHOWING listener: show and huh hooks", BubbleHooks.attachedKeys().size() == 2, BubbleHooks.attachedKeys());
        listen.remove();
        c.check("all gone: nothing", BubbleHooks.attachedKeys().isEmpty() && !BubbleStates.ticking(), BubbleHooks.attachedKeys());
        c.check("bad registrations refused", threw(() -> track(subs, TownKitBubbles.hide(OWNER, coin())))
            && threw(() -> track(subs, TownKitBubbles.swap(OWNER, hungry(),
                new BubbleIcon(ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "never_registered"), Valence.NEUTRAL))))
            && threw(() -> track(subs, TownKitBubbles.addIdleThought(OWNER, coin(), 0))) && BubbleHooks.attachedKeys().isEmpty(), BubbleHooks.attachedKeys());
        c.check("a blank owner refused (like shop, skins and moves)", threw(() -> track(subs, TownKitBubbles.hide(" ", hungry())))
            && threw(() -> track(subs, TownKitBubbles.swap("", hungry(), BubbleIcon.towntopia("status_sad"))))
            && threw(() -> track(subs, TownKitBubbles.addIdleThought(" ", coin(), 1)))
            && threw(() -> track(subs, TownKitBubbles.state(" ", BubbleState.builder(ResourceLocation.fromNamespaceAndPath(TownKit.MODID,
                "test_blank_owner"), coin(), v -> false).build())))
            && BubbleHooks.attachedKeys().isEmpty(), BubbleHooks.attachedKeys());
    }

    private static Subscription track(List<Subscription> subs, Subscription s) {
        subs.add(s);
        return s;
    }

    private static String onStateKey() {
        return "village.social.ThoughtBubbles#onState(NpcEntity,ServerLevel,VillagerMemory,ThoughtState,float,boolean)";
    }

    /** A state true for one villager, a player near: its bubble shows within 40 ticks (one check round). */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "townkit_bubble_states_shows")
    public static void addonStateShows(GameTestHelper helper) {
        // the per-tick watch is set up here, not in the scene's body: onEachTick schedules a task for every tick left, and
        // doing that from inside a scheduled task (the body) grows the test's task map while the game iterates it, which
        // can run the body a second time
        AtomicReference<Scene> watched = new AtomicReference<>();
        AtomicLong start = new AtomicLong();
        AtomicInteger shownAfter = new AtomicInteger(-1);
        helper.onEachTick(() -> {
            Scene s = watched.get();
            if (s != null && shownAfter.get() < 0 && showsCoin(s.npc())) shownAfter.set((int) (s.level().getGameTime() - start.get()));
        });
        scene(helper, "addonStateShows", s -> {
            UUID id = s.npc().getNpcId();
            s.subs().add(TownKitBubbles.state(OWNER, BubbleState.builder(ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "test_rich"), coin(),
                v -> v.id().equals(id)).priority(1).build()));
            start.set(s.level().getGameTime());
            watched.set(s);
            helper.runAfterDelay(60, () -> {
                s.c().check("shown within 40 ticks", shownAfter.get() >= 0 && shownAfter.get() <= CHECK_ROUND, shownAfter.get());
                s.c().check("as a thought", !LookSync.look(s.npc()).speech(), LookSync.look(s.npc()));
                s.end();
            });
        });
    }

    /** An addon state above priority 0 keeps Towntopia's "hungry" away while it shows; without it, hungry shows. */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "townkit_bubble_states_outranks")
    public static void addonStateOutranksHunger(GameTestHelper helper) {
        scene(helper, "addonStateOutranksHunger", s -> {
            UUID id = s.npc().getNpcId();
            Subscription state = TownKitBubbles.state(OWNER, BubbleState.builder(ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "test_feast"),
                coin(), v -> v.id().equals(id)).priority(1).build());
            s.subs().add(state);
            helper.runAfterDelay(45, () -> {
                s.c().check("the addon state shows", showsCoin(s.npc()), LookSync.look(s.npc()));
                towntopiaState(s, ThoughtState.HUNGRY);
                s.c().checkHooksOn("Towntopia's hungry suppressed", BubbleTests.packedIcon(s.npc()) == 0 && showsCoin(s.npc()), BubbleTests.packedIcon(s.npc()));
                state.remove();
                towntopiaState(s, ThoughtState.HUNGRY);
                s.c().checkHooksOn("without the state Towntopia shows hungry", BubbleTests.packedIcon(s.npc()) == ThoughtIcon.STATUS_HUNGRY.networkId()
                    && !showsCoin(s.npc()), BubbleTests.packedIcon(s.npc()));
                s.end();
            });
        });
    }

    /** A global hide of status_hungry: Towntopia never shows it (nor its "huh"); other icons still show. */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "townkit_bubble_states_hide")
    public static void hideBuiltIn(GameTestHelper helper) {
        scene(helper, "hideBuiltIn", s -> {
            s.subs().add(TownKitBubbles.hide(OWNER, hungry()));
            int huhs = huhs(s.npc(), () -> towntopiaState(s, ThoughtState.HUNGRY));
            s.c().checkHooksOn("Towntopia's hungry state shows no bubble", !s.npc().hasBubble(), BubbleTests.packedIcon(s.npc()));
            s.c().checkHooksOn("and no huh", huhs == 0, huhs);
            s.npc().showBubble(ThoughtIcon.STATUS_HUNGRY, false, 70);
            s.c().checkHooksOn("a direct showBubble neither", !s.npc().hasBubble(), BubbleTests.packedIcon(s.npc()));
            int sadHuhs = huhs(s.npc(), () -> towntopiaState(s, ThoughtState.UNHAPPY));
            s.c().check("other icons still show, with their huh", BubbleTests.packedIcon(s.npc()) == ThoughtIcon.STATUS_SAD.networkId() && sadHuhs == 1,
                BubbleTests.packedIcon(s.npc()) + ", huhs " + sadHuhs);
            s.end();
        });
    }

    /** A hide of status_hungry for one villager: hidden on that villager only; removing it shows hungry again. */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "townkit_bubble_states_hide_one")
    public static void hideOneVillager(GameTestHelper helper) {
        scene(helper, "hideOneVillager", s -> {
            Villager other = new VillagerHandle(s.level().getServer(), shared.mgr().getVillageId(), UUID.randomUUID(), null);
            Subscription elsewhere = TownKitBubbles.hide(OWNER, other, hungry());
            s.subs().add(elsewhere);
            towntopiaState(s, ThoughtState.HUNGRY);
            s.c().check("another villager's hide doesn't touch this one", BubbleTests.packedIcon(s.npc()) == ThoughtIcon.STATUS_HUNGRY.networkId(),
                BubbleTests.packedIcon(s.npc()));
            TownKitBubbles.clear(s.npc());
            Subscription own = TownKitBubbles.hide(OWNER, s.villager(), hungry());
            s.subs().add(own);
            int huhs = huhs(s.npc(), () -> towntopiaState(s, ThoughtState.HUNGRY));
            s.c().checkHooksOn("its own hide: no hungry bubble, no huh", !s.npc().hasBubble() && huhs == 0, BubbleTests.packedIcon(s.npc()) + ", huhs " + huhs);
            s.npc().showBubble(ThoughtIcon.STATUS_SAD, false, 70);
            s.c().check("other icons still show on it", BubbleTests.packedIcon(s.npc()) == ThoughtIcon.STATUS_SAD.networkId(),
                BubbleTests.packedIcon(s.npc()));
            own.remove();
            s.npc().showBubble(ThoughtIcon.STATUS_HUNGRY, false, 70);
            s.c().check("removed: hungry shows again", BubbleTests.packedIcon(s.npc()) == ThoughtIcon.STATUS_HUNGRY.networkId(),
                BubbleTests.packedIcon(s.npc()));
            s.end();
        });
    }

    /**
     * A swap of status_hungry to an addon icon for one villager beats a global swap to status_sad; removing it brings
     * the global one back, removing that Towntopia's icon.
     */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "townkit_bubble_states_swap")
    public static void swapBuiltInToAddon(GameTestHelper helper) {
        scene(helper, "swapBuiltInToAddon", s -> {
            Subscription global = TownKitBubbles.swap(OWNER, hungry(), BubbleIcon.towntopia("status_sad"));
            Subscription own = TownKitBubbles.swap(OWNER, s.villager(), hungry(), coin());
            s.subs().add(global);
            s.subs().add(own);
            int huhs = huhs(s.npc(), () -> towntopiaState(s, ThoughtState.HUNGRY));
            s.c().checkHooksOn("the addon icon shows as a 70-tick thought", showsCoin(s.npc()) && LookSync.look(s.npc()).bubbleTicks() == 70
                && !LookSync.look(s.npc()).speech(), LookSync.look(s.npc()));
            s.c().checkHooksOn("Towntopia's bubble not shown", !s.npc().hasBubble(), BubbleTests.packedIcon(s.npc()));
            s.c().check("one huh", huhs == 1, huhs);
            s.npc().showBubble(ThoughtIcon.STATUS_HUNGRY, true, 50);
            s.c().checkHooksOn("speech kind and ticks carried over", showsCoin(s.npc()) && LookSync.look(s.npc()).speech()
                && LookSync.look(s.npc()).bubbleTicks() == 50, LookSync.look(s.npc()));
            own.remove();
            s.npc().showBubble(ThoughtIcon.STATUS_HUNGRY, false, 70);
            s.c().checkHooksOn("the global swap is back", BubbleTests.packedIcon(s.npc()) == ThoughtIcon.STATUS_SAD.networkId() && !showsCoin(s.npc()),
                BubbleTests.packedIcon(s.npc()));
            global.remove();
            s.npc().showBubble(ThoughtIcon.STATUS_HUNGRY, false, 70);
            s.c().check("no swap left: hungry", BubbleTests.packedIcon(s.npc()) == ThoughtIcon.STATUS_HUNGRY.networkId(), BubbleTests.packedIcon(s.npc()));
            s.end();
        });
    }

    /** BUBBLE_SHOWING sees Towntopia's and addon bubbles; a cancel stops them, a replace swaps the icon. */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "townkit_bubble_states_event")
    public static void bubbleShowingCancel(GameTestHelper helper) {
        scene(helper, "bubbleShowingCancel", s -> {
            UUID id = s.npc().getNpcId();
            List<TownKitEvents.BubbleShowing> seen = new ArrayList<>();
            List<ResourceLocation> cancel = new ArrayList<>(List.of(hungry().id(), COIN));
            s.subs().add(TownKitEvents.BUBBLE_SHOWING.listen(OWNER, e -> {
                if (!e.villager().id().equals(id)) return;
                seen.add(e);
                if (cancel.contains(e.icon().id())) e.cancel();
                if (e.icon().id().getPath().equals("status_sad")) e.replace(coin());
            }));
            s.npc().showBubble(ThoughtIcon.STATUS_HUNGRY, false, 70);
            s.c().checkHooksOn("Towntopia's bubble cancelled", !s.npc().hasBubble(), BubbleTests.packedIcon(s.npc()));
            s.c().checkHooksOn("the event saw it: icon, kind, ticks", seen.size() == 1 && seen.get(0).icon().equals(hungry())
                && seen.get(0).kind() == BubbleKind.THOUGHT && seen.get(0).ticks() == 70 && seen.get(0).cancelled(), seen);
            boolean shown = TownKitBubbles.show(s.npc(), coin(), BubbleKind.SPEECH, 40);
            s.c().checkHooksOn("an addon bubble too: cancelled, show returns false", !shown && seen.size() == 2 && LookSync.look(s.npc()).isEmpty(), seen.size());
            cancel.clear();
            s.npc().showBubble(ThoughtIcon.STATUS_SAD, false, 70);
            s.c().checkHooksOn("replaced: the addon icon shows instead", showsCoin(s.npc()) && !s.npc().hasBubble(), LookSync.look(s.npc()));
            s.npc().showBubble(ThoughtIcon.EMOTE_LOVE, false, 70);
            s.c().checkHooksOn("untouched icons show", BubbleTests.packedIcon(s.npc()) == ThoughtIcon.EMOTE_LOVE.networkId() && !showsCoin(s.npc()),
                BubbleTests.packedIcon(s.npc()));
            s.end();
        });
    }

    /** An addon idle thought with an overwhelming weight is what a content villager thinks. */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "townkit_bubble_states_idle")
    public static void idleThoughtShows(GameTestHelper helper) {
        scene(helper, "idleThoughtShows", s -> {
            s.subs().add(TownKitBubbles.addIdleThought(OWNER, coin(), 1_000_000));
            towntopiaState(s, ThoughtState.OK);
            s.c().checkHooksOn("the addon thought shows", showsCoin(s.npc()) && !s.npc().hasBubble(), LookSync.look(s.npc()));
            s.end();
        });
    }

    /** {@code show} plays the villager's "huh"; {@code show(..., silent = true)} doesn't. */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "townkit_bubble_states_huh")
    public static void huhUnlessSilent(GameTestHelper helper) {
        scene(helper, "huhUnlessSilent", s -> {
            int addon = huhs(s.npc(), () -> TownKitBubbles.show(s.npc(), coin(), BubbleKind.THOUGHT, 40));
            int addonSilent = huhs(s.npc(), () -> TownKitBubbles.show(s.npc(), coin(), BubbleKind.THOUGHT, 40, true));
            int towntopia = huhs(s.npc(), () -> TownKitBubbles.show(s.npc(), hungry(), BubbleKind.THOUGHT, 40));
            int towntopiaSilent = huhs(s.npc(), () -> TownKitBubbles.show(s.npc(), hungry(), BubbleKind.THOUGHT, 40, true));
            s.c().check("4-argument show: one huh each", addon == 1 && towntopia == 1, addon + ", " + towntopia);
            s.c().check("silent: none", addonSilent == 0 && towntopiaSilent == 0, addonSilent + ", " + towntopiaSilent);
            s.end();
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
