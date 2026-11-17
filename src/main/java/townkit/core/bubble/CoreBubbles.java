package townkit.core.bubble;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.social.ThoughtIcon;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import townkit.api.Subscription;
import townkit.api.bubble.BubbleIcon;
import townkit.api.bubble.BubbleKind;
import townkit.api.bubble.BubbleState;
import townkit.api.bubble.Valence;
import townkit.api.event.TownKitEvents;
import townkit.api.hooks.HookCall;
import townkit.api.spi.TownKitBubblesView;
import townkit.api.village.Villager;
import townkit.core.TownKit;
import townkit.core.building.RewrittenSites;
import townkit.core.event.DirectEvents;
import townkit.core.look.LookSync;
import townkit.core.look.VillagerLook;
import townkit.core.village.VillagerHandle;

/**
 * Mood icons for addons ({@link townkit.api.bubble.TownKitBubbles}). Towntopia's icons go through Towntopia's own
 * bubble ({@code NpcEntity.showBubble}); addon icons through the villager's {@link VillagerLook} ({@link LookSync}),
 * with Towntopia's bubble cleared, drawn on the client by {@code BubbleDrawHooks}.
 *
 * <p>The server counts down each live addon bubble and resets the look's bubble fields when it runs out (so a look with
 * nothing else goes back to EMPTY). The countdown is one server-tick listener, added with the first live addon bubble
 * and removed with the last.
 *
 * <p>When Towntopia shows a bubble ({@link #beforeTowntopiaShow}): a pending addon idle thought, else the villager's own
 * hide or swap for the icon, else the global one; then {@code BUBBLE_SHOWING}. A changed icon cancels Towntopia's call
 * and shows the result here (silently: Towntopia's "huh" follows, and {@link #beforeHuh} mutes or re-pitches it). The
 * hooks are attached by {@link BubbleHooks} only while needed. Server thread, except {@link #icon} and registrations.
 */
@EventBusSubscriber(modid = TownKit.MODID)
public final class CoreBubbles implements TownKitBubblesView {
    /** The addon icons known here (code registrations; on a client also the files). */
    public static final BubbleIcons ICONS = new BubbleIcons(TownKit.LOG::warn);
    /** Hides, swaps and idle thoughts addons registered. */
    static final BubbleRules RULES = new BubbleRules();

    private static final String TOWNTOPIA = "towntopia";
    /** Towntopia's "huh" volume for a bubble ({@code ThoughtBubbles.show}). */
    private static final float HUH_VOLUME = 0.7F;

    /** Live addon bubbles: ticks left, by entity (identity: Entity doesn't override equals). Server thread. */
    private static final Map<Entity, Integer> LIVE = new HashMap<>();
    private static volatile boolean anyLive;
    private static final Consumer<ServerTickEvent.Post> TICK = e -> tick();
    private static boolean ticking;
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private static volatile boolean warnedHooksOff;

    /** True while this class calls {@code showBubble} itself: the before-hook leaves those calls alone. */
    private static boolean own;

    /** An addon idle thought picked for {@code npc}: its next bubble, Towntopia's pick {@code from}, shows {@code to}. */
    private record PendingIdle(NpcEntity npc, ThoughtIcon from, BubbleIcon to) {
    }

    /** Towntopia's "huh" about to follow a bubble that was hidden ({@code valence} null) or changed. */
    private record HuhFix(NpcEntity npc, ThoughtIcon icon, long tick, @Nullable Valence valence) {
    }

    @Nullable
    private static PendingIdle pendingIdle;
    @Nullable
    private static HuhFix huhFix;

    @Nullable
    private static volatile List<BubbleIcon> builtIns;

    // --- icons -----------------------------------------------------------------------------------------------------

    @Override
    public BubbleIcon icon(ResourceLocation id, Valence valence) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(valence, "valence");
        if (TOWNTOPIA.equals(id.getNamespace())) {
            throw new IllegalArgumentException("towntopia: icons are Towntopia's own; use BubbleIcon.towntopia(\"" + id.getPath() + "\")");
        }
        ICONS.register(id, valence);
        return new BubbleIcon(id, valence);
    }

    @Override
    public BubbleIcon towntopia(String id) {
        ThoughtIcon icon = ThoughtIcon.byId(id);
        if (icon == null) throw new IllegalArgumentException("Towntopia has no bubble icon '" + id + "'");
        return of(icon);
    }

    @Override
    public List<BubbleIcon> builtIns() {
        List<BubbleIcon> b = builtIns;
        if (b == null) {
            List<BubbleIcon> all = new ArrayList<>();
            for (ThoughtIcon icon : ThoughtIcon.values()) all.add(of(icon));
            builtIns = b = List.copyOf(all);
        }
        return b;
    }

    private static BubbleIcon of(ThoughtIcon icon) {
        return new BubbleIcon(ResourceLocation.fromNamespaceAndPath(TOWNTOPIA, icon.id()), valence(icon.valence()));
    }

    private static Valence valence(ThoughtIcon.Valence v) {
        return switch (v) {
            case POSITIVE -> Valence.POSITIVE;
            case NEUTRAL -> Valence.NEUTRAL;
            case NEGATIVE -> Valence.NEGATIVE;
        };
    }

    /** Towntopia's icon for a {@code towntopia:} id; null for an addon icon or an unknown id. */
    @Nullable
    private static ThoughtIcon towntopiaIcon(BubbleIcon icon) {
        return TOWNTOPIA.equals(icon.id().getNamespace()) ? ThoughtIcon.byId(icon.id().getPath()) : null;
    }

    /** The valence that pitches {@code icon}'s "huh": Towntopia's own for its icons, the registered one for addon icons. */
    private static Valence pitch(BubbleIcon icon) {
        ThoughtIcon t = towntopiaIcon(icon);
        if (t != null) return valence(t.valence());
        Valence registered = ICONS.registered(icon.id());
        return registered != null ? registered : icon.valence();
    }

    /** Whether {@code icon} can show: one of Towntopia's, or an addon icon registered here. */
    private static boolean known(BubbleIcon icon) {
        return TOWNTOPIA.equals(icon.id().getNamespace()) ? towntopiaIcon(icon) != null : ICONS.registered(icon.id()) != null;
    }

    /** Throws IllegalArgumentException for an icon that can't show (the same checks as {@link #show}). */
    private static void requireKnown(BubbleIcon icon) {
        Objects.requireNonNull(icon, "icon");
        if (TOWNTOPIA.equals(icon.id().getNamespace())) {
            if (towntopiaIcon(icon) == null) throw new IllegalArgumentException("Towntopia has no bubble icon '" + icon.id().getPath() + "'");
        } else if (ICONS.registered(icon.id()) == null) {
            throw new IllegalArgumentException("bubble icon " + icon.id() + " isn't registered: call TownKitBubbles.icon first");
        }
    }

    // --- showing ---------------------------------------------------------------------------------------------------

    @Override
    public boolean show(Entity villager, BubbleIcon icon, BubbleKind kind, int ticks, boolean silent) {
        Objects.requireNonNull(villager, "villager");
        Objects.requireNonNull(kind, "kind");
        requireKnown(icon);
        if (ticks < 1) throw new IllegalArgumentException("ticks must be 1 or more: " + ticks);
        checkServerThread(villager, "show");
        if (towntopiaIcon(icon) == null) warnIfHooksOff("an addon");
        if (!(villager instanceof NpcEntity npc) || !loaded(npc)) return false;
        BubbleIcon chosen = showing(npc, icon, kind, ticks);
        if (chosen == null) return false;
        display(npc, chosen, kind, ticks, silent);
        return true;
    }

    /** An addon state's icon, shown the way Towntopia shows its states: a thought, with the "huh". */
    static void showState(NpcEntity npc, BubbleIcon icon, int ticks) {
        if (!loaded(npc)) return;
        BubbleIcon chosen = showing(npc, icon, BubbleKind.THOUGHT, ticks);
        if (chosen != null) display(npc, chosen, BubbleKind.THOUGHT, ticks, false);
    }

    /** Fires {@code BUBBLE_SHOWING}: the icon to show, or null when a handler cancelled it. */
    @Nullable
    private static BubbleIcon showing(NpcEntity npc, BubbleIcon icon, BubbleKind kind, int ticks) {
        if (!DirectEvents.has(TownKitEvents.BUBBLE_SHOWING)) return icon;
        TownKitEvents.BubbleShowing event = new TownKitEvents.BubbleShowing(VillagerHandle.of(npc), icon, kind, ticks);
        DirectEvents.fire(TownKitEvents.BUBBLE_SHOWING, event);
        if (event.cancelled()) return null;
        BubbleIcon chosen = event.icon();
        if (!chosen.equals(icon) && !known(chosen)) {
            if (WARNED.add("replace " + chosen.id())) {
                TownKit.LOG.warn("[TownKit] a BUBBLE_SHOWING handler replaced a bubble with {}, which isn't a known icon: ignored", chosen.id());
            }
            return icon;
        }
        return chosen;
    }

    /** Shows a known icon, past the rules and the event. */
    private static void display(NpcEntity npc, BubbleIcon icon, BubbleKind kind, int ticks, boolean silent) {
        boolean speech = kind == BubbleKind.SPEECH;
        ThoughtIcon t = towntopiaIcon(icon);
        if (t != null) {
            dropAddonBubble(npc);
            own = true;
            try {
                npc.showBubble(t, speech, ticks);
            } finally {
                own = false;
            }
            if (!silent) huh(npc, pitch(icon), HUH_VOLUME);
            return;
        }
        npc.clearBubble();
        LookSync.update(npc, l -> l.withBubble(icon.id(), speech, ticks));
        LIVE.put(npc, ticks);
        liveChanged();
        if (!silent) huh(npc, pitch(icon), HUH_VOLUME);
    }

    @Override
    public void clear(Entity villager) {
        Objects.requireNonNull(villager, "villager");
        checkServerThread(villager, "clear");
        if (!(villager instanceof NpcEntity npc)) return;
        dropAddonBubble(npc);
        npc.clearBubble();
    }

    /** Whether an addon bubble is live on {@code entity}. */
    static boolean hasAddonBubble(Entity entity) {
        return LIVE.containsKey(entity);
    }

    /** Ends the addon bubble live on {@code entity}, if any: the look's bubble fields go back to EMPTY's. */
    private static void dropAddonBubble(Entity entity) {
        if (LIVE.remove(entity) == null) return;
        LookSync.update(entity, CoreBubbles::withoutBubble);
        liveChanged();
    }

    private static VillagerLook withoutBubble(VillagerLook look) {
        return look.withBubble(VillagerLook.EMPTY.bubble(), VillagerLook.EMPTY.speech(), VillagerLook.EMPTY.bubbleTicks());
    }

    /**
     * Towntopia's {@code ThoughtBubbles.huh}: the villager's ambient sound, pitched by valence (low negative, normal
     * neutral, high positive).
     */
    private static void huh(NpcEntity npc, Valence valence, float volume) {
        float base = switch (valence) {
            case NEGATIVE -> 0.8F;
            case NEUTRAL -> 1.0F;
            case POSITIVE -> 1.2F;
        };
        npc.level().playSound(null, npc.getX(), npc.getY(), npc.getZ(), SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL, volume,
            base + npc.getRandom().nextFloat() * 0.12F);
    }

    private static void checkServerThread(Entity villager, String what) {
        MinecraftServer server = villager.getServer();
        if (server == null || !server.isSameThread()) {
            throw new IllegalStateException("TownKitBubbles." + what + " is server thread only (and needs a server-side entity)");
        }
    }

    /** Alive in its server level (the level knows it by its id). */
    private static boolean loaded(Entity entity) {
        return !entity.isRemoved() && entity.level() instanceof ServerLevel level && level.getEntity(entity.getId()) == entity;
    }

    // --- Towntopia's bubbles (hooks attached by BubbleHooks) --------------------------------------------------------

    /** After Towntopia's {@code showBubble}: its bubble replaces a live addon one. */
    static void afterTowntopiaShow(HookCall call) {
        if (call.self() instanceof NpcEntity npc && !npc.level().isClientSide()) dropAddonBubble(npc);
    }

    /** Before Towntopia's {@code showBubble}: idle thought, rules, then the event decide what shows. */
    static void beforeTowntopiaShow(HookCall call) {
        if (own || !(call.self() instanceof NpcEntity npc) || npc.level().isClientSide()) return;
        huhFix = null;
        Object[] a = call.args();
        if (!(a[0] instanceof ThoughtIcon icon)) return;
        BubbleKind kind = Boolean.TRUE.equals(a[1]) ? BubbleKind.SPEECH : BubbleKind.THOUGHT;
        int ticks = Math.max(1, (Integer) a[2]);
        BubbleIcon original = of(icon);
        BubbleIcon chosen = original;
        PendingIdle idle = pendingIdle;
        pendingIdle = null;
        if (idle != null && idle.npc() == npc && idle.from() == icon) {
            chosen = idle.to();
        } else {
            BubbleRules.Decision d = RULES.decide(npc.getNpcId(), original.id());
            if (d.hidden()) chosen = null;
            else if (d.replacement() != null) chosen = d.replacement();
        }
        if (chosen != null) chosen = showing(npc, chosen, kind, ticks);
        if (original.equals(chosen)) return; // Towntopia's own call goes ahead
        call.cancel();
        if (chosen != null) display(npc, chosen, kind, ticks, true);
        huhFix = new HuhFix(npc, icon, npc.level().getGameTime(), chosen == null ? null : pitch(chosen));
    }

    /** Before {@code ThoughtBubbles.huh}: the "huh" right after a hidden bubble is muted, after a changed one re-pitched. */
    static void beforeHuh(HookCall call) {
        HuhFix fix = huhFix;
        Object[] a = call.args();
        if (fix == null || a[0] != fix.npc() || a[2] != fix.icon() || fix.npc().level().getGameTime() != fix.tick()) return;
        huhFix = null;
        call.cancel();
        if (fix.valence() != null) huh(fix.npc(), fix.valence(), (Float) a[3]);
    }

    /** After Towntopia's {@code idleThought}: the weighted pick, addon thoughts included. */
    static void afterIdleThought(HookCall call) {
        BubbleIcon pick = RULES.pickIdle((RandomSource) call.args()[0]);
        if (pick == null) return;
        ThoughtIcon t = towntopiaIcon(pick);
        if (t != null) {
            call.setReturnValue(t);
            return;
        }
        NpcEntity npc = BubbleStates.thinking();
        if (npc != null && call.returnValue() instanceof ThoughtIcon from) pendingIdle = new PendingIdle(npc, from, pick);
    }

    /** Drops what the hooks kept between two calls (when the hooks go). */
    static void forgetPending() {
        pendingIdle = null;
        huhFix = null;
        BubbleStates.forgetThinking();
    }

    // --- registrations ---------------------------------------------------------------------------------------------

    @Override
    public Subscription state(String owner, BubbleState state) {
        checkOwner(owner);
        Objects.requireNonNull(state, "state");
        requireKnown(state.icon());
        state.resolved().ifPresent(CoreBubbles::requireKnown);
        warnIfHooksOff(owner);
        return subscription(BubbleStates.add(state));
    }

    @Override
    public Subscription rule(String owner, @Nullable Villager villager, BubbleIcon builtIn, @Nullable BubbleIcon replacement) {
        checkOwner(owner);
        Objects.requireNonNull(builtIn, "builtIn");
        if (towntopiaIcon(builtIn) == null) throw new IllegalArgumentException(builtIn.id() + " isn't one of Towntopia's icons");
        if (replacement != null) requireKnown(replacement);
        warnIfHooksOff(owner);
        Runnable remove = RULES.add(villager == null ? null : villager.id(), builtIn.id(), replacement);
        BubbleHooks.refresh();
        return subscription(() -> {
            remove.run();
            BubbleHooks.refresh();
        });
    }

    @Override
    public Subscription addIdleThought(String owner, BubbleIcon icon, int weight) {
        checkOwner(owner);
        requireKnown(icon);
        if (weight < 1) throw new IllegalArgumentException("weight must be 1 or more: " + weight);
        warnIfHooksOff(owner);
        Runnable remove = RULES.addIdle(icon, weight);
        BubbleHooks.refresh();
        return subscription(() -> {
            remove.run();
            BubbleHooks.refresh();
        });
    }

    private static void checkOwner(String owner) {
        Objects.requireNonNull(owner, "owner");
        if (owner.isBlank()) throw new IllegalArgumentException("owner is blank");
    }

    /** With every Towntopia hook off, addon pop-ups, states, hides, swaps and idle thoughts do nothing: said once. */
    private static void warnIfHooksOff(String owner) {
        if (!warnedHooksOff && RewrittenSites.GLOBAL.hooksOff()) {
            warnedHooksOff = true;
            TownKit.LOG.warn("[TownKit] bubbles: {} registered or showed an addon bubble, but every Towntopia hook is switched off in "
                + "townkit-hooks.properties, so addon pop-ups, states, hides, swaps and idle thoughts do nothing (Towntopia's own icons "
                + "still show)", owner);
        }
    }

    private static Subscription subscription(Runnable remove) {
        AtomicBoolean done = new AtomicBoolean();
        return new Subscription() {
            @Override
            public void remove() {
                if (done.compareAndSet(false, true)) remove.run();
            }

            @Override
            public boolean isDisabled() {
                return false;
            }
        };
    }

    // --- countdown -------------------------------------------------------------------------------------------------

    /** Whether an addon bubble is live anywhere (any thread). */
    static boolean anyLive() {
        return anyLive;
    }

    /** The countdown listener and the replace hook follow whether any addon bubble is live. */
    private static void liveChanged() {
        boolean now = !LIVE.isEmpty();
        if (now == anyLive && now == ticking) return;
        anyLive = now;
        if (now && !ticking) {
            ticking = true;
            NeoForge.EVENT_BUS.addListener(TICK);
        } else if (!now && ticking) {
            ticking = false;
            NeoForge.EVENT_BUS.unregister(TICK);
        }
        BubbleHooks.refresh();
    }

    private static void tick() {
        Iterator<Map.Entry<Entity, Integer>> it = LIVE.entrySet().iterator();
        List<Entity> expired = null;
        while (it.hasNext()) {
            Map.Entry<Entity, Integer> e = it.next();
            Entity entity = e.getKey();
            int left = e.getValue() - 1;
            if (left > 0 && loaded(entity)) {
                e.setValue(left);
                continue;
            }
            it.remove();
            // an entity that left its level already lost its look (LookSync drops it); one still there loses the bubble
            if (loaded(entity)) {
                if (expired == null) expired = new ArrayList<>();
                expired.add(entity);
            }
        }
        if (expired != null) {
            for (Entity entity : expired) LookSync.update(entity, CoreBubbles::withoutBubble);
        }
        liveChanged();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LIVE.clear();
        liveChanged();
        pendingIdle = null;
        huhFix = null;
    }

    // --- for tests -------------------------------------------------------------------------------------------------

    /** How many addon bubbles are live. */
    public static int liveCount() {
        return LIVE.size();
    }

    /** Whether the countdown's tick listener is on. */
    public static boolean ticking() {
        return ticking;
    }
}
