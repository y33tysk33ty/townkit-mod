package townkit.test;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.visitor.NomadEntity;
import com.alex.test.entity.visitor.VisitorEntity;
import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import townkit.api.Subscription;
import townkit.api.skin.SkinRef;
import townkit.api.skin.TownKitSkins;
import townkit.core.TownKit;
import townkit.core.look.LookSync;
import townkit.core.net.VillagerLookPayload;
import townkit.core.skin.CoreSkins;
import townkit.core.skin.SkinOverrides;
import townkit.core.village.VillagerHandle;

/**
 * Contract tests for per-villager skins ({@link TownKitSkins#set}, {@link TownKitSkins#setTemporary},
 * {@link TownKitSkins#of}): the skin goes into the villager's {@code VillagerLook} (so {@link LookSync} sends it to every
 * tracking client), is saved with the villager or not, and comes back after a real chunk unload and reload. No test
 * village: a stray Towntopia villager (no village, as a waiting settler) is enough, and is kept by its chunk. Whatever
 * way a test ends, its subscriptions are removed and its entities discarded (a test listener does it too).
 */
@GameTestHolder(TownKit.MODID)
@PrefixGameTestTemplate(false)
public final class SkinTests {
    private static final String OWNER = "townkit-test";
    private static final String OTHER = "townkit-test-other";
    private static final SkinRef MERCHANT = SkinRef.towntopia("merchant");
    private static final SkinRef ENVOY = SkinRef.of(ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "check_skin"));
    private static final SkinRef SOLDIER = SkinRef.of(ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "test_soldier"));
    /** Far from every test village and GameTest structure: only this test loads it. */
    private static final BlockPos FAR = new BlockPos(-21000, 0, 21000);

    private SkinTests() {
    }

    /** Captures LookSync's sends; every entity is watched by {@code watchers}. */
    private static final class Capture implements LookSync.Channel {
        final List<VillagerLookPayload> sent = new ArrayList<>();
        final List<ServerPlayer> watchers;

        Capture(List<ServerPlayer> watchers) {
            this.watchers = watchers;
        }

        @Override
        public List<ServerPlayer> watching(Entity entity) {
            return watchers;
        }

        @Override
        public void send(ServerPlayer to, VillagerLookPayload payload) {
            sent.add(payload);
        }

        @Nullable
        SkinRef lastSkin(Entity entity) {
            for (int i = sent.size() - 1; i >= 0; i--) {
                if (sent.get(i).entityId() == entity.getId()) return sent.get(i).look().skin();
            }
            return null;
        }
    }

    /** What a test registered and spawned: removed whatever way the test ends. */
    private static final class Mess {
        final List<Subscription> subs = new ArrayList<>();
        final List<Entity> entities = new ArrayList<>();
        final List<Runnable> more = new ArrayList<>();

        Subscription track(Subscription s) {
            subs.add(s);
            return s;
        }

        void cleanUp() {
            subs.forEach(Subscription::remove);
            subs.clear();
            entities.forEach(e -> {
                if (!e.isRemoved()) e.discard();
            });
            entities.clear();
            more.forEach(Runnable::run);
            more.clear();
        }
    }

    private static Mess mess(GameTestHelper helper) {
        Mess m = new Mess();
        helper.testInfo.addListener(new GameTestListener() {
            @Override
            public void testStructureLoaded(GameTestInfo info) {
            }

            @Override
            public void testPassed(GameTestInfo info, GameTestRunner runner) {
                m.cleanUp();
            }

            @Override
            public void testFailed(GameTestInfo info, GameTestRunner runner) {
                m.cleanUp();
            }

            @Override
            public void testAddedForRerun(GameTestInfo old, GameTestInfo fresh, GameTestRunner runner) {
            }
        });
        return m;
    }

    /** A Towntopia villager with no village (a waiting settler, which no village takes in), standing at {@code at}. */
    private static NpcEntity stray(ServerLevel level, BlockPos at, Mess m) {
        NpcEntity npc = DynamicVillages.NPC.get().create(level);
        if (npc == null) throw new IllegalStateException("no NpcEntity made");
        npc.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0F, 0.0F);
        npc.addTag("dynamicvillages.waiting_settler");
        level.addFreshEntity(npc);
        m.entities.add(npc);
        return npc;
    }

    private static boolean threw(Class<? extends Throwable> kind, Runnable r) {
        try {
            r.run();
            return false;
        } catch (Throwable t) {
            return kind.isInstance(t);
        }
    }

    /** Runs {@code r} on a new thread and returns what it threw (null for nothing). */
    @Nullable
    private static Throwable offThread(Runnable r) {
        AtomicReference<Throwable> out = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                r.run();
            } catch (Throwable e) {
                out.set(e);
            }
        }, "townkit-skin-test");
        t.start();
        try {
            t.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out.get();
    }

    private static FakePlayer watcher(GameTestHelper helper) {
        return FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.nameUUIDFromBytes("townkit-skin-watcher".getBytes()), "townkit_skin_w"));
    }

    private static List<SkinOverrides.Saved> saved(GameTestHelper helper, NpcEntity npc) {
        return CoreSkins.saved(npc.getNpcId(), helper.getLevel().getServer());
    }

    /**
     * {@code set} puts the skin in the villager's look, which goes to every watcher; it is saved; a temporary skin set
     * later wins; the same owner setting again replaces its saved skin; removing unwinds to the earlier skin, then to
     * none (look and saved data empty). Also the argument and thread checks.
     */
    @GameTest(template = "empty", batch = "townkit_skins")
    public static void setSyncsToLook(GameTestHelper helper) {
        Checks c = new Checks("setSyncsToLook");
        Mess m = mess(helper);
        NpcEntity npc = stray(helper.getLevel(), helper.absolutePos(new BlockPos(1, 2, 1)), m);
        townkit.api.village.Villager villager = VillagerHandle.of(npc);
        Capture capture = new Capture(List.of(watcher(helper)));
        LookSync.Channel old = LookSync.swapChannelForTests(capture);
        try {
            Subscription first = m.track(TownKitSkins.set(OWNER, villager, MERCHANT));
            c.check("set: the look carries the skin", MERCHANT.equals(LookSync.look(npc).skin()), LookSync.look(npc));
            c.check("set: watchers get it", MERCHANT.equals(capture.lastSkin(npc)), capture.sent);
            c.check("of reads it", Optional.of(MERCHANT).equals(TownKitSkins.of(npc)), TownKitSkins.of(npc));
            c.check("saved with the villager", saved(helper, npc).equals(List.of(new SkinOverrides.Saved(OWNER, MERCHANT))), saved(helper, npc));

            Subscription temp = m.track(TownKitSkins.setTemporary(OTHER, npc, ENVOY));
            c.check("a later skin wins", ENVOY.equals(capture.lastSkin(npc)) && Optional.of(ENVOY).equals(TownKitSkins.of(npc)), capture.sent);
            Subscription second = m.track(TownKitSkins.set(OWNER, villager, SOLDIER));
            c.check("same owner again: replaces its saved skin, goes on top", Optional.of(SOLDIER).equals(TownKitSkins.of(npc))
                && saved(helper, npc).equals(List.of(new SkinOverrides.Saved(OWNER, SOLDIER))), saved(helper, npc));

            second.remove();
            c.check("removing the top brings back the one under it", Optional.of(ENVOY).equals(TownKitSkins.of(npc))
                && ENVOY.equals(capture.lastSkin(npc)), TownKitSkins.of(npc));
            c.check("its saved skin is gone", saved(helper, npc).isEmpty(), saved(helper, npc));
            first.remove();
            c.check("a replaced subscription removes nothing", Optional.of(ENVOY).equals(TownKitSkins.of(npc)), TownKitSkins.of(npc));
            temp.remove();
            c.check("all removed: look empty again", LookSync.look(npc).isEmpty() && TownKitSkins.of(npc).isEmpty(), LookSync.look(npc));
            c.check("watchers told: no skin", capture.lastSkin(npc) == null && !capture.sent.isEmpty()
                && capture.sent.get(capture.sent.size() - 1).look().isEmpty(), capture.sent);

            c.check("blank owner refused", threw(IllegalArgumentException.class, () -> TownKitSkins.set(" ", villager, MERCHANT)), "");
            c.check("null skin refused", threw(NullPointerException.class, () -> TownKitSkins.setTemporary(OWNER, npc, null)), "");
            Villager vanilla = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 3, 2, 1);
            m.entities.add(vanilla);
            c.check("setTemporary on a non-Towntopia entity refused", threw(IllegalArgumentException.class,
                () -> TownKitSkins.setTemporary(OWNER, vanilla, MERCHANT)), "");
            Throwable offSet = offThread(() -> TownKitSkins.set(OWNER, villager, MERCHANT));
            c.check("set off the server thread throws IllegalStateException", offSet instanceof IllegalStateException, offSet);
            Throwable offTemp = offThread(() -> TownKitSkins.setTemporary(OWNER, npc, MERCHANT));
            c.check("setTemporary off the server thread throws IllegalStateException", offTemp instanceof IllegalStateException, offTemp);
            Throwable offOf = offThread(() -> TownKitSkins.of(npc));
            c.check("of off the server thread throws IllegalStateException", offOf instanceof IllegalStateException, offOf);
            c.check("nothing left after the off-thread calls", LookSync.look(npc).isEmpty() && saved(helper, npc).isEmpty(), LookSync.look(npc));
        } catch (RuntimeException e) {
            TownKit.LOG.error("[TownKit test] setSyncsToLook threw", e);
            c.check("no exception", false, e);
        } finally {
            LookSync.swapChannelForTests(old);
            m.cleanUp();
        }
        c.finish(helper);
    }

    /**
     * A temporary skin isn't saved, goes with the entity, and an entity that isn't in its level gets nothing.
     */
    @GameTest(template = "empty", batch = "townkit_skins")
    public static void temporaryNotSaved(GameTestHelper helper) {
        Checks c = new Checks("temporaryNotSaved");
        Mess m = mess(helper);
        try {
            NpcEntity npc = stray(helper.getLevel(), helper.absolutePos(new BlockPos(1, 2, 1)), m);
            m.track(TownKitSkins.setTemporary(OWNER, npc, ENVOY));
            c.check("drawn with the skin", ENVOY.equals(LookSync.look(npc).skin()), LookSync.look(npc));
            c.check("not saved", saved(helper, npc).isEmpty(), saved(helper, npc));
            npc.discard();
            c.check("gone with the entity", TownKitSkins.of(npc).isEmpty() && LookSync.look(npc).isEmpty(), TownKitSkins.of(npc));

            Subscription onRemoved = m.track(TownKitSkins.setTemporary(OWNER, npc, ENVOY));
            c.check("a removed entity gets nothing", TownKitSkins.of(npc).isEmpty() && LookSync.look(npc).isEmpty(), TownKitSkins.of(npc));
            onRemoved.remove();
            NpcEntity never = DynamicVillages.NPC.get().create(helper.getLevel());
            if (never != null) {
                Subscription s = m.track(TownKitSkins.setTemporary(OWNER, never, ENVOY));
                c.check("an entity never added isn't drawn with it", LookSync.look(never).isEmpty(), LookSync.look(never));
                s.remove();
                c.check("removing it leaves nothing held for that entity", TownKitSkins.of(never).isEmpty() && !CoreSkins.waiting(never),
                    TownKitSkins.of(never));
            }
            NpcEntity unloadedVillager = stray(helper.getLevel(), helper.absolutePos(new BlockPos(2, 2, 2)), m);
            townkit.api.village.Villager handle = VillagerHandle.of(unloadedVillager);
            unloadedVillager.discard();
            Subscription gone = m.track(TownKitSkins.set(OWNER, handle, ENVOY));
            c.check("a villager that no longer exists gets nothing saved", saved(helper, unloadedVillager).isEmpty(), saved(helper, unloadedVillager));
            gone.remove();
        } catch (RuntimeException e) {
            TownKit.LOG.error("[TownKit test] temporaryNotSaved threw", e);
            c.check("no exception", false, e);
        } finally {
            m.cleanUp();
        }
        c.finish(helper);
    }

    /**
     * Skins set from inside an {@code EntityJoinLevelEvent} listener (the natural place to put a temporary skin back;
     * NeoForge fires it before the level holds the entity) are drawn once the entity is in the level, and their
     * subscriptions work: removing the temporary skin brings back the saved one, removing that leaves nothing.
     */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "townkit_skins_join")
    public static void setDuringJoin(GameTestHelper helper) {
        Checks c = new Checks("setDuringJoin");
        Mess m = mess(helper);
        NpcEntity npc = DynamicVillages.NPC.get().create(helper.getLevel());
        if (npc == null) {
            c.check("an NpcEntity made", false, "null");
            c.finish(helper);
            return;
        }
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        npc.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0F, 0.0F);
        npc.addTag("dynamicvillages.waiting_settler");
        Subscription[] subs = new Subscription[2];
        boolean[] findable = {true};
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> listener = e -> {
            if (e.getEntity() != npc) return;
            findable[0] = helper.getLevel().getEntity(npc.getId()) == npc;
            subs[0] = m.track(TownKitSkins.set(OWNER, VillagerHandle.of(npc), MERCHANT));
            subs[1] = m.track(TownKitSkins.setTemporary(OTHER, npc, ENVOY));
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(listener);
        m.more.add(() -> net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener));
        try {
            helper.getLevel().addFreshEntity(npc);
        } finally {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener);
        }
        m.entities.add(npc);
        c.check("the listener ran while the level couldn't find the entity yet", subs[1] != null && !findable[0], findable[0]);
        helper.runAfterDelay(5, () -> {
            try {
                c.check("the temporary skin set during join reaches the look", ENVOY.equals(LookSync.look(npc).skin())
                    && Optional.of(ENVOY).equals(TownKitSkins.of(npc)), LookSync.look(npc));
                c.check("the saved skin set during join is saved", saved(helper, npc).equals(List.of(new SkinOverrides.Saved(OWNER, MERCHANT))),
                    saved(helper, npc));
                if (subs[1] != null) subs[1].remove();
                c.check("removing the temporary one brings back the saved one", MERCHANT.equals(LookSync.look(npc).skin()), LookSync.look(npc));
                if (subs[0] != null) subs[0].remove();
                c.check("removing both leaves nothing", LookSync.look(npc).isEmpty() && saved(helper, npc).isEmpty() && !CoreSkins.waiting(npc),
                    LookSync.look(npc));
            } catch (RuntimeException e) {
                TownKit.LOG.error("[TownKit test] setDuringJoin threw", e);
                c.check("no exception", false, e);
            }
            m.cleanUp();
            c.finish(helper);
        });
    }

    /** Visitors and nomads (Towntopia people that aren't village villagers) take a temporary skin like villagers. */
    @GameTest(template = "empty", batch = "townkit_skins")
    public static void setOnVisitor(GameTestHelper helper) {
        Checks c = new Checks("setOnVisitor");
        Mess m = mess(helper);
        Capture capture = new Capture(List.of(watcher(helper)));
        LookSync.Channel old = LookSync.swapChannelForTests(capture);
        try {
            check(c, m, capture, "visitor", () -> DynamicVillages.VISITOR.get().create(helper.getLevel()), helper.absolutePos(new BlockPos(1, 2, 1)));
            check(c, m, capture, "nomad", () -> DynamicVillages.NOMAD.get().create(helper.getLevel()), helper.absolutePos(new BlockPos(3, 2, 1)));
        } catch (RuntimeException e) {
            TownKit.LOG.error("[TownKit test] setOnVisitor threw", e);
            c.check("no exception", false, e);
        } finally {
            LookSync.swapChannelForTests(old);
            m.cleanUp();
        }
        c.finish(helper);
    }

    private static void check(Checks c, Mess m, Capture capture, String what, Supplier<? extends PathfinderMob> make, BlockPos at) {
        PathfinderMob e = make.get();
        if (e == null) {
            c.check(what + " made", false, "null");
            return;
        }
        c.check(what + " is a Towntopia person", e instanceof VisitorEntity || e instanceof NomadEntity, e.getClass());
        e.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0F, 0.0F);
        e.level().addFreshEntity(e);
        m.entities.add(e);
        Subscription s = m.track(TownKitSkins.setTemporary(OWNER, e, MERCHANT));
        c.check(what + ": skin in its look, sent", MERCHANT.equals(LookSync.look(e).skin()) && MERCHANT.equals(capture.lastSkin(e)), LookSync.look(e));
        c.check(what + ": of reads it", Optional.of(MERCHANT).equals(TownKitSkins.of(e)), TownKitSkins.of(e));
        s.remove();
        c.check(what + ": removed", LookSync.look(e).isEmpty() && TownKitSkins.of(e).isEmpty(), LookSync.look(e));
    }

    /**
     * A saved skin comes back after a real chunk unload and reload (the look itself is dropped with the entity, by
     * design), and a temporary skin set on top of it doesn't: the stray villager stands in a far chunk, which is
     * un-forced until the villager unloads with it, then forced again until it is back.
     */
    @GameTest(template = "empty", timeoutTicks = 2000, batch = "townkit_skins_unload")
    public static void skinOverrideSurvivesUnload(GameTestHelper helper) {
        Checks c = new Checks("skinOverrideSurvivesUnload");
        Mess m = mess(helper);
        ServerLevel level = helper.getLevel();
        ChunkPos far = new ChunkPos(FAR);
        m.more.add(() -> level.setChunkForced(far.x, far.z, false));
        NpcEntity[] npc = {null};
        UUID[] id = {null};
        Subscription[] saved = {null};
        int[] phase = {0};
        int[] tick = {0};
        int[] mark = {0};
        boolean[] over = {false};
        helper.onEachTick(() -> {
            if (over[0]) return;
            tick[0]++;
            boolean done;
            try {
                done = step(helper, c, m, level, far, npc, id, saved, phase, tick[0], mark);
            } catch (RuntimeException | Error e) {
                TownKit.LOG.error("[TownKit test] skinOverrideSurvivesUnload threw", e);
                c.check("no exception", false, e);
                done = true;
            }
            if (done) {
                over[0] = true;
                m.cleanUp();
                c.finish(helper);
            }
        });
    }

    private static boolean step(GameTestHelper helper, Checks c, Mess m, ServerLevel level, ChunkPos far, NpcEntity[] npc, UUID[] id,
                                Subscription[] saved, int[] phase, int t, int[] mark) {
        switch (phase[0]) {
            case 0 -> {
                level.setChunkForced(far.x, far.z, true);
                level.getChunk(far.x, far.z);
                mark[0] = t;
                phase[0] = -1;
            }
            case -1 -> {
                // an entity added before its chunk's entities are loaded isn't in the level yet (the level can't find it)
                if (!level.isPositionEntityTicking(far.getMiddleBlockPosition(0))) {
                    if (t - mark[0] < 200) return false;
                    c.check("the far chunk is entity-ticking within 200 ticks", false, "not yet");
                    return true;
                }
                BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, far.getMiddleBlockPosition(0));
                level.setBlock(top, Blocks.GLASS.defaultBlockState(), 3);
                npc[0] = stray(level, top.above(), m);
                id[0] = npc[0].getUUID();
                c.check("the villager is in the level", level.getEntity(npc[0].getId()) == npc[0], npc[0]);
                saved[0] = m.track(TownKitSkins.set(OWNER, VillagerHandle.of(npc[0]), MERCHANT));
                m.track(TownKitSkins.setTemporary(OTHER, npc[0], ENVOY));
                c.check("before the unload: the temporary skin wins", ENVOY.equals(LookSync.look(npc[0]).skin()), LookSync.look(npc[0]));
                level.setChunkForced(far.x, far.z, false);
                mark[0] = t;
                phase[0] = 1;
            }
            case 1 -> {
                if (!npc[0].isRemoved()) {
                    if (t - mark[0] < 900) return false;
                    c.check("the far chunk unloaded within 900 ticks", false, "still loaded: " + level.hasChunk(far.x, far.z));
                    return true;
                }
                c.check("removed by the chunk unload", npc[0].getRemovalReason() == Entity.RemovalReason.UNLOADED_TO_CHUNK, npc[0].getRemovalReason());
                c.check("unloaded: its look and skins are dropped", LookSync.look(npc[0]).isEmpty() && TownKitSkins.of(npc[0]).isEmpty(),
                    LookSync.look(npc[0]));
                c.check("unloaded: the saved skin is kept", saved(helper, npc[0]).equals(List.of(new SkinOverrides.Saved(OWNER, MERCHANT))),
                    saved(helper, npc[0]));
                level.setChunkForced(far.x, far.z, true);
                mark[0] = t;
                phase[0] = 2;
            }
            case 2 -> {
                Entity again = level.getEntity(id[0]);
                boolean back = again instanceof NpcEntity n && LookSync.look(n).skin() != null;
                if (!back) {
                    if (t - mark[0] < 400) {
                        LoadWait.notYet();
                        return false;
                    }
                    c.check("the villager loads back with its skin within 400 ticks", false, again == null ? "not back" : LookSync.look(again));
                    return true;
                }
                NpcEntity reloaded = (NpcEntity) again;
                m.entities.add(reloaded);
                c.check("a new entity, the same villager", reloaded != npc[0] && reloaded.getNpcId().equals(npc[0].getNpcId()), reloaded.getNpcId());
                c.check("reloaded: the saved skin is drawn, the temporary one is gone", MERCHANT.equals(LookSync.look(reloaded).skin())
                    && Optional.of(MERCHANT).equals(TownKitSkins.of(reloaded)), LookSync.look(reloaded));
                saved[0].remove();
                c.check("removing the subscription after the reload clears the skin and the saved data", LookSync.look(reloaded).isEmpty()
                    && TownKitSkins.of(reloaded).isEmpty() && saved(helper, reloaded).isEmpty(), LookSync.look(reloaded));
                return true;
            }
            default -> {
                return true;
            }
        }
        return false;
    }
}
