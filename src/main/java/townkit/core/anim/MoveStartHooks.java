package townkit.core.anim;

import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import townkit.api.anim.Move;
import townkit.api.event.TownKitEvents;
import townkit.api.hooks.HookCall;
import townkit.api.village.Villager;
import townkit.core.TownKit;
import townkit.core.building.RewrittenSites;
import townkit.core.event.DirectEvents;
import townkit.core.hook.HookInfo;
import townkit.core.hook.HookManifest;
import townkit.core.hook.Hooks;
import townkit.core.village.VillagerHandle;

/**
 * The server side of a move starting: a before-hook on Towntopia's {@code NpcEntity#setAnimation}, attached only while
 * some swap is registered ({@link CoreSwaps}) or {@code MOVE_STARTING} has a listener.
 *
 * <p>When Towntopia asks for a move: the server swap for it ({@link CoreSwaps#serverSwap}) applies; then, if the
 * villager's move changes, {@code MOVE_STARTING} fires. The outcome: keep Towntopia's call; cancel it (the villager
 * keeps its move); redirect it (cancel, then set the replacement through Towntopia's own {@code setAnimation}); or, for
 * an addon move a handler chose, keep Towntopia's call and give the entity a one-off client swap
 * ({@link CoreSwaps#setOneOff}) until Towntopia asks for another move. Towntopia sets the same move again and again
 * (each sets its timer): the answer is remembered per entity and applied again without firing the event. Server thread.
 */
public final class MoveStartHooks {
    static final String KEY = "entity.NpcEntity#setAnimation(VillagerAnimationState)";

    /** What happens to Towntopia's call. */
    private record Decision(Kind kind, @Nullable VillagerAnimationState redirect, @Nullable ResourceLocation oneOff) {
        static final Decision PASS = new Decision(Kind.PASS, null, null);
        static final Decision CANCEL = new Decision(Kind.CANCEL, null, null);
    }

    private enum Kind { PASS, CANCEL, REDIRECT, ONE_OFF }

    /** @param version the swap table's version the decision was made with: another version is a miss */
    private record Memo(VillagerAnimationState requested, Decision decision, long version) {
    }

    @Nullable
    private static Hooks.Entry attached;
    private static boolean warnedMissing;
    /** The last move Towntopia asked for, per entity, and what was done with it. */
    private static final Map<NpcEntity, Memo> MEMO = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<ResourceLocation> WARNED = ConcurrentHashMap.newKeySet();
    /** True while TownKit itself calls setAnimation for a redirect. Server thread. */
    private static boolean redirecting;

    /** {@code MOVE_STARTING} attaches the hook with its first listener. */
    public static final DirectEvents.Lifecycle EVENTS = new DirectEvents.Lifecycle() {
        @Override
        public boolean available() {
            return !RewrittenSites.GLOBAL.hooksOff() && HookManifest.get().byKey.containsKey(KEY);
        }

        @Override
        public void attach() {
            refresh();
        }

        @Override
        public void detach() {
            refresh();
        }
    };

    private MoveStartHooks() {
    }

    /** Attaches the hook while it is needed, detaches it otherwise. Any thread. */
    static synchronized void refresh() {
        boolean wanted = !CoreSwaps.TABLE.isEmpty() || DirectEvents.has(TownKitEvents.MOVE_STARTING);
        if (wanted && attached == null) {
            HookInfo h = HookManifest.get().byKey.get(KEY);
            if (h == null) {
                if (!warnedMissing) {
                    warnedMissing = true;
                    TownKit.LOG.warn("[TownKit] can't find {}: Towntopia's moves can't be swapped on the server or followed", KEY);
                }
                return;
            }
            attached = Hooks.add(h, TownKit.MODID, MoveStartHooks::before, false);
        } else if (!wanted && attached != null) {
            Hooks.remove(attached);
            attached = null;
            MEMO.clear();
        }
    }

    /** The hooks attached now, as {@code "before <key>"} (for tests). */
    public static synchronized List<String> attachedKeys() {
        List<String> out = new ArrayList<>();
        if (attached != null) out.add("before " + KEY);
        return out;
    }

    private static void before(HookCall call) {
        if (redirecting || !(call.self() instanceof NpcEntity npc) || npc.level().isClientSide()
            || !(call.args()[0] instanceof VillagerAnimationState state) || npc.isPreviewingAnimation()) {
            return;
        }
        Memo m = MEMO.get(npc);
        long version = CoreSwaps.TABLE.version();
        Decision d;
        if (m != null && m.requested() == state && (m.version() == version || m.decision().kind() == Kind.ONE_OFF)) {
            // the same move again: the same answer (a handler's one-off for this start stands even if the swaps changed)
            d = m.decision();
        } else {
            // another move, or the swaps changed since: decide again (the event fires only if the move really changes)
            if (m != null && m.decision().kind() == Kind.ONE_OFF) CoreSwaps.clearOneOff(npc);
            d = decide(npc, state);
            MEMO.put(npc, new Memo(state, d, version));
        }
        switch (d.kind()) {
            case PASS -> {
            }
            case CANCEL -> call.cancel();
            case REDIRECT -> {
                call.cancel();
                redirecting = true;
                try {
                    npc.setAnimation(d.redirect());
                } finally {
                    redirecting = false;
                }
            }
            case ONE_OFF -> CoreSwaps.setOneOff(npc, Move.towntopia(state.name()).id(), d.oneOff());
        }
    }

    private static Decision decide(NpcEntity npc, VillagerAnimationState state) {
        Move asked = Move.towntopia(state.name());
        ResourceLocation swapped = CoreSwaps.serverSwap(npc, asked.id());
        Move chosen = swapped == null ? asked : Move.of(swapped);
        boolean byEvent = false;
        if (state != npc.getAnimation() && DirectEvents.has(TownKitEvents.MOVE_STARTING)) {
            TownKitEvents.MoveStarting e = new TownKitEvents.MoveStarting(VillagerHandle.of(npc), chosen);
            DirectEvents.fire(TownKitEvents.MOVE_STARTING, e);
            if (e.cancelled()) return Decision.CANCEL;
            if (!e.move().equals(chosen)) {
                if (e.move().isTowntopia() && stateOf(e.move()) == null) {
                    warnUnknown(e.move());
                } else {
                    chosen = e.move();
                    byEvent = true;
                }
            }
        }
        if (chosen.equals(asked)) return Decision.PASS;
        if (chosen.isTowntopia()) {
            VillagerAnimationState to = stateOf(chosen);
            return to == null || to == state ? Decision.PASS : new Decision(Kind.REDIRECT, to, null);
        }
        // an addon move: Towntopia's move stays on the server; a swap from the table reaches clients in the look
        return byEvent ? new Decision(Kind.ONE_OFF, null, chosen.id()) : Decision.PASS;
    }

    /**
     * Fires {@code MOVE_STARTING} for an addon's play: the move to play, or null when a handler cancelled it. A
     * replacement with a Towntopia move Towntopia doesn't have is ignored (logged once).
     */
    @Nullable
    static Move playStarting(Villager villager, Move move) {
        if (!DirectEvents.has(TownKitEvents.MOVE_STARTING)) return move;
        TownKitEvents.MoveStarting e = new TownKitEvents.MoveStarting(villager, move);
        DirectEvents.fire(TownKitEvents.MOVE_STARTING, e);
        if (e.cancelled()) return null;
        if (e.move().isTowntopia() && !e.move().equals(move) && stateOf(e.move()) == null) {
            warnUnknown(e.move());
            return move;
        }
        return e.move();
    }

    private static void warnUnknown(Move move) {
        if (WARNED.add(move.id())) {
            TownKit.LOG.warn("[TownKit] a MOVE_STARTING handler replaced a move with {}, which Towntopia doesn't have: ignored (logged once)", move.id());
        }
    }

    /** Towntopia's move state for one of its moves, or null when it has none of that name. */
    @Nullable
    static VillagerAnimationState stateOf(Move move) {
        if (!move.isTowntopia()) return null;
        try {
            return VillagerAnimationState.valueOf(move.name());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
