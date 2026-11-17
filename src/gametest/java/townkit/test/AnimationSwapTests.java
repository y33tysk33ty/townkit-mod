package townkit.test;

import com.alex.test.DynamicVillages;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import townkit.api.Subscription;
import townkit.api.anim.Move;
import townkit.api.anim.PlayMode;
import townkit.api.anim.TownKitAnimations;
import townkit.api.event.TownKitEvents;
import townkit.core.TownKit;
import townkit.core.anim.CoreSwaps;
import townkit.core.anim.MoveStartHooks;
import townkit.core.look.LookSync;
import townkit.core.look.VillagerLook;
import townkit.core.net.VillagerLookPayload;
import townkit.core.village.VillagerHandle;

/**
 * Contract tests for replacing Towntopia's moves ({@link TownKitAnimations#replace}) and
 * {@link TownKitEvents#MOVE_STARTING}: a cancel keeps the villager's move, a replacement with one of Towntopia's moves
 * is set by Towntopia instead, one with an addon move reaches clients as a one-off swap in the look until Towntopia's
 * move changes; global swaps reach every villager's look (also ones that join later), a villager's own beat them, and
 * a villager's own are saved and come back after a real chunk unload and reload. Stray villagers (no village); each
 * test in its own batch, since global swaps and listeners see every villager. Whatever way a test ends, its
 * subscriptions are removed and its villagers discarded (a test listener does it too).
 */
@GameTestHolder(TownKit.MODID)
@PrefixGameTestTemplate(false)
public final class AnimationSwapTests {
    private static final String OWNER = "townkit-test";
    private static final Move RAISE = Move.of(ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "check_raise"));
    private static final Move MARCH = Move.of(ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "check_march"));
    private static final Move WALK = Move.towntopia("WALK");
    private static final Move SALUTE = Move.towntopia("SALUTE");
    private static final Move SHRUG = Move.towntopia("SHRUG");
    private static final Move THINK = Move.towntopia("THINK");
    /** Far from every test village, GameTest structure and the other tests' far chunks: only this test loads it. */
    private static final BlockPos FAR = new BlockPos(-23000, 0, 21000);

    private AnimationSwapTests() {
    }

    /** What a test spawned or registered: undone whatever way the test ends. */
    private static final class Mess {
        final List<Entity> entities = new ArrayList<>();
        final List<Runnable> more = new ArrayList<>();

        Subscription keep(Subscription s) {
            more.add(s::remove);
            return s;
        }

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

    /** Captures LookSync's sends; every entity is watched by one fake player. */
    private static final class Capture implements LookSync.Channel {
        final List<VillagerLookPayload> sent = new ArrayList<>();
        final List<ServerPlayer> watchers;

        Capture(ServerLevel level) {
            watchers = List.of(FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes("townkit-swap-watcher".getBytes()), "townkit_swap_w")));
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

    /** A Towntopia villager with no village (a waiting settler), standing at {@code at}. */
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
        }, "townkit-swap-test");
        t.start();
        try {
            t.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out.get();
    }

    /**
     * A MOVE_STARTING cancel keeps the villager's move (and isn't asked again while Towntopia sets the same move); a play
     * a handler cancels doesn't play; the hook is attached only while someone listens.
     */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "townkit_swap_cancel")
    public static void moveStartingCancel(GameTestHelper helper) {
        Checks c = new Checks("moveStartingCancel");
        Mess m = mess(helper);
        try {
            NpcEntity npc = stray(helper.getLevel(), helper.absolutePos(new BlockPos(1, 2, 1)), m);
            c.check("no start hook before anyone listens", MoveStartHooks.attachedKeys().isEmpty(), MoveStartHooks.attachedKeys());
            npc.setAnimation(VillagerAnimationState.IDLE);
            AtomicInteger heard = new AtomicInteger();
            List<Move> moves = new ArrayList<>();
            Subscription sub = m.keep(TownKitEvents.MOVE_STARTING.listen(OWNER, e -> {
                if (e.villager().entity().orElse(null) != npc) return;
                heard.incrementAndGet();
                moves.add(e.move());
                if (e.move().equals(SALUTE)) e.cancel();
            }));
            c.checkHooksOn("the start hook is attached while someone listens", !MoveStartHooks.attachedKeys().isEmpty(), MoveStartHooks.attachedKeys());
            npc.setAnimation(VillagerAnimationState.SALUTE);
            c.checkHooksOn("cancelled: the villager keeps its move", npc.getAnimation() == VillagerAnimationState.IDLE, npc.getAnimation());
            c.checkHooksOn("the handler was told SALUTE was starting", moves.equals(List.of(SALUTE)), moves);
            npc.setAnimation(VillagerAnimationState.SALUTE);
            c.checkHooksOn("Towntopia setting it again: still cancelled, not asked again", npc.getAnimation() == VillagerAnimationState.IDLE
                && heard.get() == 1, npc.getAnimation() + " " + heard.get());
            npc.setAnimation(VillagerAnimationState.SHRUG);
            c.checkHooksOn("another move starts as usual", npc.getAnimation() == VillagerAnimationState.SHRUG && heard.get() == 2, npc.getAnimation());
            npc.setAnimation(VillagerAnimationState.SHRUG);
            c.checkHooksOn("the same move set again isn't a start", heard.get() == 2, heard.get());
            TownKitAnimations.PlayResult r = TownKitAnimations.play(OWNER, npc, SALUTE, PlayMode.loop());
            c.checkHooksOn("a play the handler cancels doesn't play", !r.playing() && LookSync.look(npc).move() == null, r + " " + LookSync.look(npc));
            c.checkHooksOn("... and the handler heard it", heard.get() == 3, heard.get());
            sub.remove();
            c.check("the start hook goes with the last listener", MoveStartHooks.attachedKeys().isEmpty(), MoveStartHooks.attachedKeys());
            npc.setAnimation(VillagerAnimationState.SALUTE);
            c.check("no listener: Towntopia's move as usual", npc.getAnimation() == VillagerAnimationState.SALUTE, npc.getAnimation());
        } catch (RuntimeException e) {
            TownKit.LOG.error("[TownKit test] moveStartingCancel threw", e);
            c.check("no exception", false, e);
        } finally {
            m.cleanUp();
        }
        c.finish(helper);
    }

    /**
     * A MOVE_STARTING replacement with one of Towntopia's moves is set by Towntopia instead; with an addon move,
     * Towntopia's move stays and clients get a one-off swap in the look until Towntopia's move changes; a replacement
     * Towntopia doesn't have is ignored (for a play too); a replaced play plays the replacement.
     */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "townkit_swap_replace")
    public static void moveStartingReplace(GameTestHelper helper) {
        Checks c = new Checks("moveStartingReplace");
        Mess m = mess(helper);
        try {
            NpcEntity npc = stray(helper.getLevel(), helper.absolutePos(new BlockPos(1, 2, 1)), m);
            npc.setAnimation(VillagerAnimationState.IDLE);
            m.keep(TownKitEvents.MOVE_STARTING.listen(OWNER, e -> {
                if (e.villager().entity().orElse(null) != npc) return;
                if (e.move().equals(SALUTE)) e.replace(SHRUG);
                else if (e.move().equals(THINK)) e.replace(RAISE);
                else if (e.move().equals(Move.towntopia("KICK"))) e.replace(Move.towntopia("NO_SUCH_MOVE"));
                else if (e.move().equals(Move.towntopia("ARM_ON_HIP"))) e.replace(RAISE);
            }));
            npc.setAnimation(VillagerAnimationState.SALUTE);
            c.checkHooksOn("replaced with a Towntopia move: Towntopia sets that", npc.getAnimation() == VillagerAnimationState.SHRUG, npc.getAnimation());
            npc.setAnimation(VillagerAnimationState.SALUTE);
            c.checkHooksOn("... again when Towntopia sets SALUTE again", npc.getAnimation() == VillagerAnimationState.SHRUG, npc.getAnimation());
            npc.setAnimation(VillagerAnimationState.THINK);
            c.check("replaced with an addon move: Towntopia's move stays on the server", npc.getAnimation() == VillagerAnimationState.THINK,
                npc.getAnimation());
            c.checkHooksOn("... and clients get a one-off swap", Map.of(THINK.id(), RAISE.id()).equals(LookSync.look(npc).moveSwaps()), LookSync.look(npc));
            npc.setAnimation(VillagerAnimationState.IDLE);
            c.check("the one-off ends when Towntopia's move changes", LookSync.look(npc).moveSwaps().isEmpty(), LookSync.look(npc));
            npc.setAnimation(VillagerAnimationState.KICK);
            c.check("a Towntopia move Towntopia doesn't have is ignored", npc.getAnimation() == VillagerAnimationState.KICK, npc.getAnimation());
            TownKitAnimations.play(OWNER, npc, Move.towntopia("ARM_ON_HIP"), PlayMode.loop());
            c.checkHooksOn("a replaced play plays the replacement", RAISE.id().equals(LookSync.look(npc).move()), LookSync.look(npc));
            TownKitAnimations.play(OWNER, npc, Move.towntopia("KICK"), PlayMode.loop());
            c.check("a play replaced with a Towntopia move Towntopia doesn't have plays the original",
                Move.towntopia("KICK").id().equals(LookSync.look(npc).move()), LookSync.look(npc));
        } catch (RuntimeException e) {
            TownKit.LOG.error("[TownKit test] moveStartingReplace threw", e);
            c.check("no exception", false, e);
        } finally {
            m.cleanUp();
        }
        c.finish(helper);
    }

    /**
     * Global swaps: a swap of walking or to an addon move reaches every loaded villager's look and watchers (one that
     * joins later too); a swap to a Towntopia move is done on the server; a villager's own swap beats the global one, is
     * saved, and removing it brings the global one back; removing everything leaves nothing (no hook, empty looks).
     */
    @GameTest(template = "empty", timeoutTicks = 100, batch = "townkit_swap_global")
    public static void globalReplaceSyncs(GameTestHelper helper) {
        Checks c = new Checks("globalReplaceSyncs");
        Mess m = mess(helper);
        ServerLevel level = helper.getLevel();
        Capture capture = new Capture(level);
        LookSync.Channel old = LookSync.swapChannelForTests(capture);
        m.more.add(() -> LookSync.swapChannelForTests(old));
        NpcEntity a;
        Subscription walk;
        try {
            a = stray(level, helper.absolutePos(new BlockPos(1, 2, 1)), m);
            a.setAnimation(VillagerAnimationState.IDLE);
            walk = m.keep(TownKitAnimations.replace(OWNER, WALK, MARCH));
            c.check("a global walk swap reaches a loaded villager's look", Map.of(WALK.id(), MARCH.id()).equals(LookSync.look(a).moveSwaps()),
                LookSync.look(a));
            VillagerLook sent = capture.last(a);
            c.check("... and its watchers", sent != null && Map.of(WALK.id(), MARCH.id()).equals(sent.moveSwaps()), sent);
            Subscription salute = m.keep(TownKitAnimations.replace(OWNER, SALUTE, SHRUG));
            a.setAnimation(VillagerAnimationState.SALUTE);
            c.checkHooksOn("a swap to a Towntopia move is done on the server", a.getAnimation() == VillagerAnimationState.SHRUG, a.getAnimation());
            c.check("... and isn't sent to clients", !LookSync.look(a).moveSwaps().containsKey(SALUTE.id()), LookSync.look(a));
            a.setAnimation(VillagerAnimationState.SALUTE);
            c.checkHooksOn("Towntopia setting SALUTE again: still swapped", a.getAnimation() == VillagerAnimationState.SHRUG, a.getAnimation());
            salute.remove();
            a.setAnimation(VillagerAnimationState.SALUTE);
            c.check("removed while the hook stays attached (walk swap): Towntopia's SALUTE again at once",
                a.getAnimation() == VillagerAnimationState.SALUTE, a.getAnimation());
            salute = m.keep(TownKitAnimations.replace(OWNER, SALUTE, SHRUG));
            a.setAnimation(VillagerAnimationState.SALUTE);
            c.checkHooksOn("a swap added while Towntopia keeps setting the same move applies at once", a.getAnimation() == VillagerAnimationState.SHRUG,
                a.getAnimation());
            Subscription think = m.keep(TownKitAnimations.replace(OWNER, THINK, RAISE));
            a.setAnimation(VillagerAnimationState.THINK);
            c.check("a swap to an addon move keeps Towntopia's move on the server", a.getAnimation() == VillagerAnimationState.THINK, a.getAnimation());
            c.check("... and goes to clients", RAISE.id().equals(LookSync.look(a).moveSwaps().get(THINK.id())), LookSync.look(a));

            Subscription own = m.keep(TownKitAnimations.replace(OWNER, VillagerHandle.of(a), WALK, RAISE));
            c.check("a villager's own swap beats the global one", RAISE.id().equals(LookSync.look(a).moveSwaps().get(WALK.id())), LookSync.look(a));
            List<CoreSwaps.Saved> saved = CoreSwaps.saved(a.getNpcId(), level.getServer());
            c.check("... and is saved with it", saved.equals(List.of(new CoreSwaps.Saved(OWNER, WALK.id(), RAISE.id()))), saved);
            own.remove();
            c.check("removing it brings back the global one", MARCH.id().equals(LookSync.look(a).moveSwaps().get(WALK.id())), LookSync.look(a));
            c.check("... and forgets the saved one", CoreSwaps.saved(a.getNpcId(), level.getServer()).isEmpty(), "");

            c.check("blank owner refused", threw(IllegalArgumentException.class, () -> TownKitAnimations.replace(" ", WALK, MARCH)), "");
            c.check("only Towntopia's moves can be replaced", threw(IllegalArgumentException.class, () -> TownKitAnimations.replace(OWNER, MARCH, WALK)), "");
            Throwable off = offThread(() -> TownKitAnimations.replace(OWNER, VillagerHandle.of(a), WALK, RAISE));
            c.check("a villager's own swap off the server thread: IllegalStateException", off instanceof IllegalStateException, off);
            salute.remove();
            think.remove();
        } catch (RuntimeException e) {
            TownKit.LOG.error("[TownKit test] globalReplaceSyncs threw", e);
            c.check("no exception", false, e);
            m.cleanUp();
            c.finish(helper);
            return;
        }
        NpcEntity b = stray(level, helper.absolutePos(new BlockPos(3, 2, 1)), m);
        helper.runAfterDelay(3, () -> {
            try {
                c.check("a villager that joins later gets the global swap", Map.of(WALK.id(), MARCH.id()).equals(LookSync.look(b).moveSwaps()),
                    LookSync.look(b));
                walk.remove();
                c.check("removing the global swap empties the looks", LookSync.look(a).isEmpty() && LookSync.look(b).isEmpty(),
                    LookSync.look(a) + " " + LookSync.look(b));
                c.check("nothing held, no start hook", !CoreSwaps.anyHeld() && MoveStartHooks.attachedKeys().isEmpty(), MoveStartHooks.attachedKeys());
            } catch (RuntimeException e) {
                TownKit.LOG.error("[TownKit test] globalReplaceSyncs threw", e);
                c.check("no exception", false, e);
            } finally {
                m.cleanUp();
            }
            c.finish(helper);
        });
    }

    /**
     * A villager's own swap is saved and comes back after a real chunk unload and reload (the look itself is dropped
     * with the entity); its subscription still removes it afterwards (look and saved data). The glass block, the
     * villager and its saved swap are removed whatever way the test ends.
     */
    @GameTest(template = "empty", timeoutTicks = 2000, batch = "townkit_swap_unload")
    public static void perVillagerReplaceSurvivesUnload(GameTestHelper helper) {
        Checks c = new Checks("perVillagerReplaceSurvivesUnload");
        Mess m = mess(helper);
        ServerLevel level = helper.getLevel();
        ChunkPos far = new ChunkPos(FAR);
        m.more.add(() -> level.setChunkForced(far.x, far.z, false));
        NpcEntity[] npc = {null};
        UUID[] id = {null};
        Subscription[] sub = {null};
        int[] phase = {0};
        int[] tick = {0};
        int[] mark = {0};
        boolean[] over = {false};
        helper.onEachTick(() -> {
            if (over[0]) return;
            int t = ++tick[0];
            boolean done = false;
            try {
                switch (phase[0]) {
                    case 0 -> {
                        level.setChunkForced(far.x, far.z, true);
                        level.getChunk(far.x, far.z);
                        mark[0] = t;
                        phase[0] = 1;
                    }
                    case 1 -> {
                        if (!level.isPositionEntityTicking(far.getMiddleBlockPosition(0))) {
                            if (t - mark[0] < 200) break;
                            c.check("the far chunk is entity-ticking within 200 ticks", false, "not yet");
                            done = true;
                            break;
                        }
                        BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, far.getMiddleBlockPosition(0));
                        level.setBlock(top, Blocks.GLASS.defaultBlockState(), 3);
                        m.more.add(0, () -> level.setBlock(top, Blocks.AIR.defaultBlockState(), 3));
                        npc[0] = stray(level, top.above(), m);
                        id[0] = npc[0].getUUID();
                        sub[0] = TownKitAnimations.replace(OWNER, VillagerHandle.of(npc[0]), WALK, MARCH);
                        // a timeout must not leave the saved swap in the test world: the subscription works while unloaded
                        m.more.add(0, () -> sub[0].remove());
                        c.check("the swap is in the look now", Map.of(WALK.id(), MARCH.id()).equals(LookSync.look(npc[0]).moveSwaps()), LookSync.look(npc[0]));
                        level.setChunkForced(far.x, far.z, false);
                        mark[0] = t;
                        phase[0] = 2;
                    }
                    case 2 -> {
                        if (!npc[0].isRemoved()) {
                            if (t - mark[0] < 900) break;
                            c.check("the far chunk unloaded within 900 ticks", false, "still loaded");
                            done = true;
                            break;
                        }
                        c.check("removed by the chunk unload", npc[0].getRemovalReason() == Entity.RemovalReason.UNLOADED_TO_CHUNK, npc[0].getRemovalReason());
                        c.check("unloaded: its look is dropped", LookSync.look(npc[0]).isEmpty(), LookSync.look(npc[0]));
                        c.check("unloaded: the swap is still saved", !CoreSwaps.saved(npc[0].getNpcId(), level.getServer()).isEmpty(), "");
                        level.setChunkForced(far.x, far.z, true);
                        mark[0] = t;
                        phase[0] = 3;
                    }
                    case 3 -> {
                        Entity again = level.getEntity(id[0]);
                        boolean back = again instanceof NpcEntity n && !LookSync.look(n).moveSwaps().isEmpty();
                        if (!back) {
                            if (t - mark[0] < 400) {
                                LoadWait.notYet();
                                break;
                            }
                            c.check("the villager loads back with its swap within 400 ticks", false, again == null ? "not back" : LookSync.look(again));
                            done = true;
                            break;
                        }
                        m.entities.add(again);
                        c.check("a new body after the reload", again != npc[0], "");
                        c.check("its swap is back in its look", Map.of(WALK.id(), MARCH.id()).equals(LookSync.look(again).moveSwaps()), LookSync.look(again));
                        sub[0].remove();
                        c.check("the subscription removes it after the reload", LookSync.look(again).isEmpty()
                            && CoreSwaps.saved(((NpcEntity) again).getNpcId(), level.getServer()).isEmpty(), LookSync.look(again));
                        done = true;
                    }
                    default -> done = true;
                }
            } catch (RuntimeException | Error e) {
                TownKit.LOG.error("[TownKit test] perVillagerReplaceSurvivesUnload threw", e);
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
}
