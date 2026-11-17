package townkit.test;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.VillagerBrain;
import com.mojang.authlib.GameProfile;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import townkit.api.anim.Move;
import townkit.api.anim.PlayMode;
import townkit.api.anim.TownKitAnimations;
import townkit.api.anim.TownKitAnimations.PlayResult;
import townkit.core.TownKit;
import townkit.core.anim.CoreAnimations;
import townkit.core.anim.MoveLengths;
import townkit.core.anim.MovePlays;
import townkit.core.brain.ActivityRegistry;
import townkit.core.look.LookSync;
import townkit.core.look.VillagerLook;
import townkit.core.net.VillagerLookPayload;
import townkit.core.village.VillagerHandle;

/**
 * Contract tests for moves ({@link TownKitAnimations#play}, {@link TownKitAnimations#stop}): a play goes into the
 * villager's {@code VillagerLook} (so {@link LookSync} sends it to every tracking client) with a sequence number and its
 * once flag; a once play and a loopFor end by themselves; a loop on a {@code Villager} is saved and comes back after a
 * real chunk unload and reload; holding still is refused during a Towntopia job. Stray villagers (no village) for the
 * plain tests; the hold test reuses {@link BubbleStateTests}' shared test village (a villager with a brain). Whatever
 * way a test ends, its plays are stopped and its entities discarded (a test listener does it too).
 */
@GameTestHolder(TownKit.MODID)
@PrefixGameTestTemplate(false)
public final class AnimationTests {
    private static final String OWNER = "townkit-test";
    private static final String OTHER = "townkit-test-other";
    private static final Move RAISE = Move.of(ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "check_raise"));
    private static final Move SALUTE = Move.towntopia("SALUTE");
    /** Far from every test village, GameTest structure and SkinTests' far chunk: only this test loads it. */
    private static final BlockPos FAR = new BlockPos(-21000, 0, -21000);

    private AnimationTests() {
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
        VillagerLook last(Entity entity) {
            for (int i = sent.size() - 1; i >= 0; i--) {
                if (sent.get(i).entityId() == entity.getId()) return sent.get(i).look();
            }
            return null;
        }
    }

    /** What a test spawned or changed: undone whatever way the test ends. */
    private static final class Mess {
        final List<Entity> entities = new ArrayList<>();
        final List<Runnable> more = new ArrayList<>();

        void cleanUp() {
            entities.forEach(e -> {
                if (!e.isRemoved()) {
                    TownKitAnimations.stop(e);
                    e.discard();
                }
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

    @Nullable
    private static Throwable offThread(Runnable r) {
        AtomicReference<Throwable> out = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                r.run();
            } catch (Throwable e) {
                out.set(e);
            }
        }, "townkit-anim-test");
        t.start();
        try {
            t.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out.get();
    }

    /**
     * A once play goes into the look (move, a new sequence number, once) and to every watcher, lasts its clip's length
     * as read from the mod files, then clears itself; a loopFor ends after its ticks; {@code stop} clears at once; a
     * once play is never saved. Also the argument and thread checks.
     */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "townkit_anim")
    public static void playOnceSyncs(GameTestHelper helper) {
        Checks c = new Checks("playOnceSyncs");
        Mess m = mess(helper);
        Capture capture = new Capture(List.of(FakePlayerFactory.get(helper.getLevel(),
            new GameProfile(UUID.nameUUIDFromBytes("townkit-anim-watcher".getBytes()), "townkit_anim_w"))));
        LookSync.Channel old = LookSync.swapChannelForTests(capture);
        m.more.add(() -> LookSync.swapChannelForTests(old));
        int raiseTicks = MoveLengths.onceTicks(RAISE.id());
        NpcEntity once = stray(helper.getLevel(), helper.absolutePos(new BlockPos(1, 2, 1)), m);
        NpcEntity timed = stray(helper.getLevel(), helper.absolutePos(new BlockPos(3, 2, 1)), m);
        try {
            c.check("the server read the placeholder clip's length from the mod files (1.5 s)", raiseTicks == MoveLengths.ticks(1.5), raiseTicks);
            c.check("... and Towntopia's salute from Towntopia's jar (1 s)", MoveLengths.onceTicks(SALUTE.id()) == MoveLengths.ticks(1.0),
                MoveLengths.onceTicks(SALUTE.id()));
            c.check("WAVE resolves to the cheer clip Towntopia plays for it", "mixamo_cheer".equals(townkit.core.anim.TowntopiaMoves.clip("WAVE")),
                townkit.core.anim.TowntopiaMoves.clip("WAVE"));
            PlayResult r = TownKitAnimations.play(OWNER, once, RAISE, PlayMode.once());
            c.check("play: playing, not held", r.playing() && !r.held(), r);
            VillagerLook look = LookSync.look(once);
            c.check("the look carries the move, once", RAISE.id().equals(look.move()) && look.moveOnce() && look.moveSeq() != 0, look);
            c.check("watchers get it", look.equals(capture.last(once)), capture.sent);
            int seq = look.moveSeq();
            TownKitAnimations.play(OWNER, once, RAISE, PlayMode.once());
            c.check("the same move again: a new sequence number", LookSync.look(once).moveSeq() != seq, LookSync.look(once));
            c.check("a once play on a villager is never saved", CoreAnimations.saved(once.getNpcId(), helper.getLevel().getServer()) == null
                && TownKitAnimations.play(OWNER, VillagerHandle.of(once), RAISE, PlayMode.once()).playing()
                && CoreAnimations.saved(once.getNpcId(), helper.getLevel().getServer()) == null, "");

            TownKitAnimations.play(OWNER, timed, SALUTE, PlayMode.loop());
            c.check("a loop: not once", SALUTE.id().equals(LookSync.look(timed).move()) && !LookSync.look(timed).moveOnce(), LookSync.look(timed));
            TownKitAnimations.stop(timed);
            c.check("stop clears the look at once", LookSync.look(timed).isEmpty() && CoreAnimations.playing(timed).isEmpty()
                && capture.last(timed) != null && capture.last(timed).isEmpty(), LookSync.look(timed));
            TownKitAnimations.play(OWNER, timed, SALUTE, PlayMode.loopFor(5));

            c.check("blank owner refused", threw(IllegalArgumentException.class, () -> TownKitAnimations.play(" ", once, RAISE, PlayMode.once())), "");
            c.check("null move refused", threw(NullPointerException.class, () -> TownKitAnimations.play(OWNER, once, null, PlayMode.once())), "");
            Villager vanilla = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 3, 2, 3);
            m.entities.add(vanilla);
            c.check("a non-Towntopia entity refused", threw(IllegalArgumentException.class,
                () -> TownKitAnimations.play(OWNER, vanilla, RAISE, PlayMode.once())), "");
            Throwable offPlay = offThread(() -> TownKitAnimations.play(OWNER, once, RAISE, PlayMode.once()));
            c.check("play off the server thread throws IllegalStateException", offPlay instanceof IllegalStateException, offPlay);
            Throwable offSaved = offThread(() -> TownKitAnimations.play(OWNER, VillagerHandle.of(once), RAISE, PlayMode.loop()));
            c.check("play (villager) off the server thread throws IllegalStateException", offSaved instanceof IllegalStateException, offSaved);
            Throwable offStop = offThread(() -> TownKitAnimations.stop(once));
            c.check("stop off the server thread throws IllegalStateException", offStop instanceof IllegalStateException, offStop);
            c.check("the once play is still on after the refused calls", RAISE.id().equals(LookSync.look(once).move()), LookSync.look(once));
        } catch (RuntimeException e) {
            TownKit.LOG.error("[TownKit test] playOnceSyncs threw", e);
            c.check("no exception", false, e);
            m.cleanUp();
            c.finish(helper);
            return;
        }
        helper.runAfterDelay(raiseTicks + 2, () -> {
            try {
                c.check("the once play cleared itself after its clip", LookSync.look(once).isEmpty() && CoreAnimations.playing(once).isEmpty(),
                    LookSync.look(once));
                c.check("watchers told: no move", capture.last(once) != null && capture.last(once).isEmpty(), capture.last(once));
                c.check("the loopFor(5) ended too", LookSync.look(timed).isEmpty() && CoreAnimations.playing(timed).isEmpty(), LookSync.look(timed));
            } catch (RuntimeException e) {
                TownKit.LOG.error("[TownKit test] playOnceSyncs threw", e);
                c.check("no exception", false, e);
            }
            m.cleanUp();
            c.check("nothing left playing, no tick listener", !CoreAnimations.anyHeld(), "");
            c.finish(helper);
        });
    }

    /**
     * Per-owner stops: one owner's stop leaves another owner's play and saved loop alone; the owner's own stop removes
     * only what it started or saved, and another owner's saved loop then plays again; {@code stop(Entity)} stops all.
     */
    @GameTest(template = "empty", batch = "townkit_anim")
    public static void ownerStopKeepsOthers(GameTestHelper helper) {
        Checks c = new Checks("ownerStopKeepsOthers");
        Mess m = mess(helper);
        try {
            NpcEntity npc = stray(helper.getLevel(), helper.absolutePos(new BlockPos(1, 2, 3)), m);
            townkit.api.village.Villager villager = VillagerHandle.of(npc);
            TownKitAnimations.play(OWNER, villager, RAISE, PlayMode.loop());
            TownKitAnimations.stop(OTHER, npc);
            TownKitAnimations.stop(OTHER, villager);
            c.check("another owner's stops leave the loop and its save", RAISE.id().equals(LookSync.look(npc).move())
                && CoreAnimations.saved(npc.getNpcId(), helper.getLevel().getServer()) != null, LookSync.look(npc));

            TownKitAnimations.play(OTHER, npc, SALUTE, PlayMode.loop());
            TownKitAnimations.stop(OWNER, npc);
            c.check("the owner's stop forgets its saved loop but leaves the other owner's current play",
                SALUTE.id().equals(LookSync.look(npc).move()) && CoreAnimations.saved(npc.getNpcId(), helper.getLevel().getServer()) == null,
                LookSync.look(npc));
            TownKitAnimations.stop(OTHER, npc);
            c.check("then the other owner's stop clears it", LookSync.look(npc).isEmpty() && CoreAnimations.playing(npc).isEmpty(), LookSync.look(npc));

            TownKitAnimations.play(OWNER, villager, RAISE, PlayMode.loop());
            TownKitAnimations.play(OTHER, npc, SALUTE, PlayMode.loop());
            TownKitAnimations.stop(OTHER, npc);
            c.check("stopping a play over another owner's saved loop brings the loop back", RAISE.id().equals(LookSync.look(npc).move())
                && !LookSync.look(npc).moveOnce(), LookSync.look(npc));
            TownKitAnimations.play(OTHER, npc, SALUTE, PlayMode.loop());
            TownKitAnimations.stop(npc);
            c.check("stop(Entity) stops everyone's play and forgets the saved loop", LookSync.look(npc).isEmpty()
                && CoreAnimations.saved(npc.getNpcId(), helper.getLevel().getServer()) == null, LookSync.look(npc));
            c.check("blank owner refused", threw(IllegalArgumentException.class, () -> TownKitAnimations.stop(" ", npc)), "");
        } catch (RuntimeException e) {
            TownKit.LOG.error("[TownKit test] ownerStopKeepsOthers threw", e);
            c.check("no exception", false, e);
        } finally {
            m.cleanUp();
        }
        c.finish(helper);
    }

    /**
     * A loop played on a {@code Villager} is saved and comes back after a real chunk unload and reload (the look itself is
     * dropped with the entity, by design); a once play on the entity on top of it isn't saved and doesn't replace the
     * saved loop. Another owner's stop leaves it alone, also while the villager is unloaded; the owner's
     * {@code stop(owner, Villager)} on an unloaded villager forgets its saved loop (a second villager then loads back
     * without it), and after the reload clears the look and the saved loop. The glass blocks, the villagers and their
     * saved loops are removed whatever way the test ends.
     */
    @GameTest(template = "empty", timeoutTicks = 2000, batch = "townkit_anim_unload")
    public static void loopingMoveSurvivesUnload(GameTestHelper helper) {
        Checks c = new Checks("loopingMoveSurvivesUnload");
        Mess m = mess(helper);
        ServerLevel level = helper.getLevel();
        ChunkPos far = new ChunkPos(FAR);
        m.more.add(() -> level.setChunkForced(far.x, far.z, false));
        NpcEntity[] npc = {null, null};
        UUID[] id = {null, null};
        int[] phase = {0};
        int[] tick = {0};
        int[] mark = {0};
        boolean[] over = {false};
        helper.onEachTick(() -> {
            if (over[0]) return;
            tick[0]++;
            boolean done;
            try {
                done = step(helper, c, m, level, far, npc, id, phase, tick[0], mark);
            } catch (RuntimeException | Error e) {
                TownKit.LOG.error("[TownKit test] loopingMoveSurvivesUnload threw", e);
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
                                int[] phase, int t, int[] mark) {
        switch (phase[0]) {
            case 0 -> {
                level.setChunkForced(far.x, far.z, true);
                level.getChunk(far.x, far.z);
                mark[0] = t;
                phase[0] = -1;
            }
            case -1 -> {
                if (!level.isPositionEntityTicking(far.getMiddleBlockPosition(0))) {
                    if (t - mark[0] < 200) return false;
                    c.check("the far chunk is entity-ticking within 200 ticks", false, "not yet");
                    return true;
                }
                BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, far.getMiddleBlockPosition(0));
                BlockPos top2 = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, far.getMiddleBlockPosition(0).east(3));
                level.setBlock(top, Blocks.GLASS.defaultBlockState(), 3);
                level.setBlock(top2, Blocks.GLASS.defaultBlockState(), 3);
                // undone before the far chunk is un-forced (Mess runs these after discarding its entities)
                m.more.add(0, () -> {
                    level.setBlock(top, Blocks.AIR.defaultBlockState(), 3);
                    level.setBlock(top2, Blocks.AIR.defaultBlockState(), 3);
                });
                npc[0] = stray(level, top.above(), m);
                npc[1] = stray(level, top2.above(), m);
                id[0] = npc[0].getUUID();
                id[1] = npc[1].getUUID();
                townkit.api.village.Villager first = VillagerHandle.of(npc[0]);
                townkit.api.village.Villager second = VillagerHandle.of(npc[1]);
                // a timeout must not leave saved loops in the test world: these work while unloaded too
                m.more.add(0, () -> {
                    TownKitAnimations.stop(OWNER, first);
                    TownKitAnimations.stop(OWNER, second);
                });
                c.check("the second villager's saved loop plays", TownKitAnimations.play(OWNER, second, RAISE, PlayMode.loop()).playing(), "");
                PlayResult r = TownKitAnimations.play(OWNER, VillagerHandle.of(npc[0]), RAISE, PlayMode.loop());
                c.check("a saved loop plays now", r.playing() && RAISE.id().equals(LookSync.look(npc[0]).move()) && !LookSync.look(npc[0]).moveOnce(),
                    LookSync.look(npc[0]));
                CoreAnimations.Saved s = CoreAnimations.saved(npc[0].getNpcId(), level.getServer());
                c.check("... and is saved with the villager", s != null && s.move().equals(RAISE.id()) && s.owner().equals(OWNER) && s.until() == -1, s);
                TownKitAnimations.play(OWNER, npc[0], SALUTE, PlayMode.once());
                c.check("a once play on top replaces it live", SALUTE.id().equals(LookSync.look(npc[0]).move()), LookSync.look(npc[0]));
                c.check("... but not the saved loop", s != null && s.equals(CoreAnimations.saved(npc[0].getNpcId(), level.getServer())), "");
                TownKitAnimations.stop(OTHER, npc[0]);
                TownKitAnimations.stop(OTHER, VillagerHandle.of(npc[0]));
                c.check("another owner's stop changes nothing", SALUTE.id().equals(LookSync.look(npc[0]).move())
                    && s != null && s.equals(CoreAnimations.saved(npc[0].getNpcId(), level.getServer())), LookSync.look(npc[0]));
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
                c.check("unloaded: its look and play are dropped", LookSync.look(npc[0]).isEmpty() && CoreAnimations.playing(npc[0]).isEmpty(),
                    LookSync.look(npc[0]));
                c.check("unloaded: the saved loop is kept", CoreAnimations.saved(npc[0].getNpcId(), level.getServer()) != null, "");
                if (!npc[1].isRemoved()) {
                    c.check("both villagers unloaded together", false, npc[1]);
                    return true;
                }
                TownKitAnimations.stop(OTHER, VillagerHandle.of(npc[0]));
                c.check("unloaded: another owner's stop(owner, Villager) leaves the saved loop", CoreAnimations.saved(npc[0].getNpcId(),
                    level.getServer()) != null, "");
                TownKitAnimations.stop(OWNER, VillagerHandle.of(npc[1]));
                c.check("unloaded: the owner's stop(owner, Villager) forgets its saved loop", CoreAnimations.saved(npc[1].getNpcId(),
                    level.getServer()) == null, "");
                level.setChunkForced(far.x, far.z, true);
                mark[0] = t;
                phase[0] = 2;
            }
            case 2 -> {
                Entity again = level.getEntity(id[0]);
                Entity again2 = level.getEntity(id[1]);
                boolean back = again instanceof NpcEntity n && LookSync.look(n).move() != null && again2 instanceof NpcEntity;
                if (!back) {
                    if (t - mark[0] < 400) {
                        LoadWait.notYet();
                        return false;
                    }
                    c.check("the villager loads back with its move within 400 ticks", false, again == null ? "not back" : LookSync.look(again));
                    return true;
                }
                NpcEntity reloaded = (NpcEntity) again;
                m.entities.add(reloaded);
                c.check("a new entity, the same villager", reloaded != npc[0] && reloaded.getNpcId().equals(npc[0].getNpcId()), reloaded.getNpcId());
                VillagerLook look = LookSync.look(reloaded);
                c.check("reloaded: the saved loop plays (not the once play)", RAISE.id().equals(look.move()) && !look.moveOnce() && look.moveSeq() != 0,
                    look);
                m.entities.add(again2);
                c.check("the second villager loads back without its forgotten loop", LookSync.look(again2).isEmpty()
                    && CoreAnimations.playing(again2).isEmpty(), LookSync.look(again2));
                TownKitAnimations.stop(OWNER, VillagerHandle.of(reloaded));
                c.check("the owner's stop(owner, Villager) after the reload clears the look and the saved loop", LookSync.look(reloaded).isEmpty()
                    && CoreAnimations.saved(reloaded.getNpcId(), level.getServer()) == null, LookSync.look(reloaded));
                return true;
            }
            default -> {
                return true;
            }
        }
        return false;
    }

    /**
     * Holding still follows TownKit's activity rules: refused while the villager runs a Towntopia job (the move still
     * plays), allowed while it is idle (TownKit's hold activity is queued on its brain), and over once the move stops.
     * The shared test village's farmer, brain suspended, its current activity set by hand.
     */
    @GameTest(template = "empty", timeoutTicks = 300, batch = "townkit_anim_hold")
    public static void holdStillDuringWorkRefused(GameTestHelper helper) {
        Checks c = new Checks("holdStillDuringWorkRefused");
        BubbleStateTests.withSharedFarmer(helper, c, farmer -> {
            VillagerModel model = VillagerHandle.of(farmer).model();
            if (model == null) {
                c.check("the farmer has a model", false, "null");
                BehaviourTests.finish(helper, c);
                return;
            }
            VillagerBrain brain = model.brain();
            Object current = field(brain, "current");
            Object forced = field(brain, "forced");
            AtomicReference<Boolean> restored = new AtomicReference<>(false);
            Runnable restore = () -> {
                if (restored.getAndSet(true)) return;
                if (!farmer.isRemoved()) TownKitAnimations.stop(farmer);
                set(brain, "current", current);
                set(brain, "forced", forced);
            };
            helper.testInfo.addListener(new GameTestListener() {
                @Override
                public void testStructureLoaded(GameTestInfo info) {
                }

                @Override
                public void testPassed(GameTestInfo info, GameTestRunner runner) {
                    restore.run();
                }

                @Override
                public void testFailed(GameTestInfo info, GameTestRunner runner) {
                    restore.run();
                }

                @Override
                public void testAddedForRerun(GameTestInfo old, GameTestInfo fresh, GameTestRunner runner) {
                }
            });
            try {
                set(brain, "forced", null);
                set(brain, "current", new BehaviourTests.HeldJob());
                PlayResult r = TownKitAnimations.play(OWNER, farmer, SALUTE, PlayMode.once().holdStill());
                c.check("during a Towntopia job: the move plays, not held", r.playing() && !r.held(), r);
                c.check("... it is in the look", SALUTE.id().equals(LookSync.look(farmer).move()), LookSync.look(farmer));
                c.check("... and nothing was queued on the brain", field(brain, "forced") == null, field(brain, "forced"));

                set(brain, "current", null);
                PlayResult held = TownKitAnimations.play(OWNER, VillagerHandle.of(farmer), RAISE, PlayMode.loopFor(100).holdStill());
                Object queued = field(brain, "forced");
                c.check("idle: held", held.playing() && held.held(), held);
                c.check("... TownKit's hold activity is queued on the brain", queued instanceof Activity a
                    && CoreAnimations.HOLD_ID.equals(ActivityRegistry.addonId(a)), queued);
                c.check("... and holds while the move plays", CoreAnimations.holding(VillagerHandle.of(farmer))
                    && CoreAnimations.playing(farmer).map(MovePlays.Play::held).orElse(false), CoreAnimations.playing(farmer));
                PlayResult visual = TownKitAnimations.play(OWNER, farmer, RAISE, PlayMode.once());
                c.check("a play without holdStill: not held, and the hold is over", visual.playing() && !visual.held()
                    && !CoreAnimations.holding(VillagerHandle.of(farmer)), visual);
                TownKitAnimations.stop(farmer);
                c.check("stop: nothing plays, nothing saved", LookSync.look(farmer).isEmpty() && CoreAnimations.playing(farmer).isEmpty()
                    && CoreAnimations.saved(farmer.getNpcId(), helper.getLevel().getServer()) == null, LookSync.look(farmer));
            } catch (RuntimeException e) {
                TownKit.LOG.error("[TownKit test] holdStillDuringWorkRefused threw", e);
                c.check("no exception", false, e);
            } finally {
                restore.run();
            }
            BehaviourTests.finish(helper, c);
        });
    }

    private static Object field(VillagerBrain brain, String name) {
        try {
            Field f = VillagerBrain.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(brain);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("can't read VillagerBrain." + name, e);
        }
    }

    private static void set(VillagerBrain brain, String name, @Nullable Object value) {
        try {
            Field f = VillagerBrain.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(brain, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("can't set VillagerBrain." + name, e);
        }
    }
}
