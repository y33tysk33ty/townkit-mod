package townkit.core.bubble;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import townkit.api.event.TownKitEvents;
import townkit.api.hooks.HookListener;
import townkit.core.TownKit;
import townkit.core.building.RewrittenSites;
import townkit.core.event.DirectEvents;
import townkit.core.hook.HookInfo;
import townkit.core.hook.HookManifest;
import townkit.core.hook.Hooks;

/**
 * Server hooks on Towntopia's bubbles. Each is attached only while something needs it and removed after, so a world
 * without addon bubbles, states, rules or {@code BUBBLE_SHOWING} listeners runs Towntopia's bubbles untouched:
 * <ul>
 *   <li>{@link #SHOW} after, while an addon bubble is live: a bubble Towntopia shows replaces it.</li>
 *   <li>{@link #SHOW} before and {@link #HUH} before, while a hide, swap or idle thought is registered or
 *   {@code BUBBLE_SHOWING} has a listener: rules, then the event, decide what shows; the "huh" Towntopia plays right
 *   after follows (muted for a hidden bubble, pitched by the replacement).</li>
 *   <li>{@link #ON_STATE} before, while a state or idle thought is registered: an addon state that outranks
 *   Towntopia's turns the call into a suppressed one, and the villager thinking is noted (for idle thoughts).</li>
 *   <li>{@link #IDLE} after, while an idle thought is registered: the weighted pick with the addon thoughts.</li>
 * </ul>
 * Server thread, except {@link #refresh} (any thread).
 */
public final class BubbleHooks {
    static final String SHOW = "entity.NpcEntity#showBubble(ThoughtIcon,boolean,int)";
    static final String HUH = "village.social.ThoughtBubbles#huh(NpcEntity,ServerLevel,ThoughtIcon,float)";
    static final String ON_STATE = "village.social.ThoughtBubbles#onState(NpcEntity,ServerLevel,VillagerMemory,ThoughtState,float,boolean)";
    static final String IDLE = "village.social.ThoughtBubbles#idleThought(RandomSource,ServerLevel)";

    private enum Slot {
        REPLACED(SHOW, true, CoreBubbles::afterTowntopiaShow),
        SHOWING(SHOW, false, CoreBubbles::beforeTowntopiaShow),
        HUH_FOLLOWS(HUH, false, CoreBubbles::beforeHuh),
        STATE(ON_STATE, false, BubbleStates::beforeOnState),
        IDLE_PICK(IDLE, true, CoreBubbles::afterIdleThought);

        final String key;
        final boolean after;
        final HookListener listener;

        Slot(String key, boolean after, HookListener listener) {
            this.key = key;
            this.after = after;
            this.listener = listener;
        }
    }

    private static final Map<Slot, Hooks.Entry> ATTACHED = new EnumMap<>(Slot.class);
    private static boolean warned;

    /** {@code BUBBLE_SHOWING} attaches the show hooks with its first listener: Towntopia's own bubbles fire it. */
    public static final DirectEvents.Lifecycle EVENTS = new DirectEvents.Lifecycle() {
        @Override
        public boolean available() {
            return !RewrittenSites.GLOBAL.hooksOff() && HookManifest.get().byKey.containsKey(SHOW) && HookManifest.get().byKey.containsKey(HUH);
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

    private BubbleHooks() {
    }

    /** Attaches what is needed now and detaches the rest. */
    static synchronized void refresh() {
        boolean idle = CoreBubbles.RULES.hasIdle();
        boolean shaping = idle || !CoreBubbles.RULES.isEmpty() || DirectEvents.has(TownKitEvents.BUBBLE_SHOWING);
        set(Slot.REPLACED, CoreBubbles.anyLive());
        set(Slot.SHOWING, shaping);
        set(Slot.HUH_FOLLOWS, shaping);
        set(Slot.STATE, idle || !BubbleStates.TICKER.isEmpty());
        set(Slot.IDLE_PICK, idle);
        if (ATTACHED.isEmpty()) CoreBubbles.forgetPending();
    }

    private static void set(Slot slot, boolean wanted) {
        Hooks.Entry e = ATTACHED.get(slot);
        if (wanted && e == null) {
            HookInfo h = HookManifest.get().byKey.get(slot.key);
            if (h == null) {
                if (!warned) {
                    warned = true;
                    TownKit.LOG.warn("[TownKit] can't find {}: TownKit can't fully follow or change Towntopia's bubbles", slot.key);
                }
                return;
            }
            ATTACHED.put(slot, Hooks.add(h, TownKit.MODID, slot.listener, slot.after));
        } else if (!wanted && e != null) {
            Hooks.remove(e);
            ATTACHED.remove(slot);
        }
    }

    /** The hooks attached right now, as {@code "before <key>"} or {@code "after <key>"} (for tests). */
    public static synchronized List<String> attachedKeys() {
        List<String> out = new ArrayList<>();
        ATTACHED.forEach((slot, e) -> out.add((slot.after ? "after " : "before ") + slot.key));
        return out;
    }
}
