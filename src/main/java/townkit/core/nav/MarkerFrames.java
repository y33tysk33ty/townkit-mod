package townkit.core.nav;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.tokens.ItemFrameTokenRegistry;
import com.alex.test.village.tokens.VillageTokenBinding;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;
import townkit.api.nav.TransportStop;

/**
 * Turns item frames holding a live kind's marker into transport stops, following Towntopia's own marker pattern
 * (its guard posts): a frame that joins the level, or that a player interacts with or hits, is checked on the next
 * level tick; the marker is bound to the village ({@code VillageTokenBinding.bind}, then {@code bindFrame}) and the
 * frame glows (what TownKit adds to a marker item is taken off again when it leaves the frame as an item); every 40 ticks the stops are reconciled (a frame gone, emptied or holding another item loses its stop),
 * within 2 ms per pass. Everything is keyed by dimension: pending checks per level, stops in each level's
 * {@link StopStore}.
 *
 * <p>A stop is refused with one log line when its frame is not in a village, is bound to another village or one in
 * another dimension, lies outside the village's route map ({@code NavGraph.isInsideBounds}), or has no routable tile
 * under it (the frame's block, or one or two below). The stop's tile may have no node yet (a place reachable only by
 * the link): it must then be a tile Towntopia's {@code makeNode} gives a routable node for.
 *
 * <p>Registered on NeoForge's bus only when the first kind is enabled ({@link LinkInstaller#attach}). Its level tick
 * and entity join listeners also serve {@link RideEngine} (ending rides whose villager or walk is gone; a villager
 * saved mid-ride).
 */
public final class MarkerFrames {
    private static final Logger LOG = LogUtils.getLogger();
    private static final long BUDGET_NANOS = 2_000_000L;
    private static final Map<ResourceKey<Level>, Set<BlockPos>> PENDING = new HashMap<>();
    private static final Map<ResourceKey<Level>, Set<BlockPos>> RETRY = new HashMap<>();
    private static final Map<ResourceKey<Level>, Integer> CURSOR = new HashMap<>();
    private static boolean registered;
    /** Set by {@link #accept} when it answered null only because the route map isn't built yet (retried later). */
    private static boolean retried;

    private MarkerFrames() {
    }

    static synchronized void register() {
        if (!registered) {
            registered = true;
            NeoForge.EVENT_BUS.register(MarkerFrames.class);
        }
    }

    private static void queue(Entity target) {
        if (target instanceof ItemFrame frame && frame.level() instanceof ServerLevel level) {
            PENDING.computeIfAbsent(level.dimension(), k -> new LinkedHashSet<>()).add(frame.blockPosition().immutable());
        }
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide()) {
            return;
        }
        if (e.getEntity() instanceof ItemFrame frame && TransportKinds.GLOBAL.kindOf(frame.getItem().getItem()) != null) {
            queue(frame);
        } else if (e.getEntity() instanceof ItemEntity item && marked(item.getItem())) {
            // a marker taken out of (or dropped with) its frame: back to the plain item, so it stacks and doesn't glow
            guarded(() -> item.setItem(unmark(item.getItem())));
        } else if (e.getEntity() instanceof NpcEntity npc && e.getLevel() instanceof ServerLevel level) {
            // a villager saved mid-ride is put on a stop
            RideEngine.onJoin(npc, level);
        }
    }

    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract e) {
        queue(e.getTarget());
    }

    @SubscribeEvent
    public static void onInteractSpecific(PlayerInteractEvent.EntityInteractSpecific e) {
        queue(e.getTarget());
    }

    @SubscribeEvent
    public static void onAttack(AttackEntityEvent e) {
        queue(e.getTarget());
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent e) {
        if (e.getEntity() instanceof ItemFrame frame && e.getLevel() instanceof ServerLevel level) {
            Entity.RemovalReason why = frame.getRemovalReason();
            if (why != null && why.shouldDestroy()) {
                guarded(() -> drop(level, frame.getUUID(), null));
            }
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent e) {
        LinkInstaller.serverStarted();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent e) {
        PENDING.clear();
        RETRY.clear();
        CURSOR.clear();
        RideEngine.serverStopped();
        LinkInstaller.serverStopped();
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post e) {
        if (!(e.getLevel() instanceof ServerLevel level)) {
            return;
        }
        RideEngine.tickLevel(level);
        if (!LinkInstaller.usable()) {
            return;
        }
        guarded(() -> {
            LinkInstaller.tickPending(level.getServer());
            Set<BlockPos> pending = PENDING.remove(level.dimension());
            if (pending != null) {
                for (BlockPos pos : pending) {
                    check(level, pos);
                }
            }
            if (level.getGameTime() % 40 == 0) {
                Set<BlockPos> retry = RETRY.remove(level.dimension());
                if (retry != null) {
                    PENDING.computeIfAbsent(level.dimension(), k -> new LinkedHashSet<>()).addAll(retry);
                }
                reconcile(level);
            }
        });
    }

    private static void guarded(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException | LinkageError t) {
            LinkInstaller.fail("checking a transport marker frame", t);
        }
    }

    /** Checks every frame at {@code pos}; a stop at {@code pos} whose frame is gone is dropped. */
    private static void check(ServerLevel level, BlockPos pos) {
        StopStore store = StopStore.of(level);
        Set<UUID> here = new HashSet<>();
        for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, new AABB(pos))) {
            if (frame.blockPosition().equals(pos) && !frame.isRemoved()) {
                here.add(frame.getUUID());
                checkFrame(level, store, frame);
            }
        }
        store.all().forEach((id, s) -> {
            if (s.framePos().equals(pos) && !here.contains(id)) {
                drop(level, id, null);
            }
        });
    }

    private static void checkFrame(ServerLevel level, StopStore store, ItemFrame frame) {
        ResourceLocation kind = TransportKinds.GLOBAL.kindOf(frame.getItem().getItem());
        if (kind == null) {
            drop(level, frame.getUUID(), frame);
            return;
        }
        TransportStop stop = accept(level, frame, kind);
        if (stop == null) {
            if (!retried) {
                drop(level, frame.getUUID(), frame);
            }
            return;
        }
        glint(frame, true);
        // a frame that moved to another village changes both (the old one loses its links over this stop)
        for (UUID changed : store.put(frame.getUUID(), stop)) {
            LinkInstaller.stopsChanged(level, changed);
        }
    }

    /** The stop {@code frame} makes, or null (logged, unless the village's route map isn't built yet: retried). */
    @Nullable
    private static TransportStop accept(ServerLevel level, ItemFrame frame, ResourceLocation kind) {
        retried = false;
        BlockPos pos = frame.blockPosition().immutable();
        ItemStack stack = frame.getItem();
        VillageManagerRegistry registry = VillageManagerRegistry.get(level.getServer());
        VillageTokenBinding binding = VillageTokenBinding.read(stack);
        VillageManager village;
        if (binding != null) {
            if (!binding.dimension().equals(level.dimension().location().toString())) {
                return refuse(level, pos, kind, "its marker is bound to a village in another dimension");
            }
            village = registry.get(binding.townHallId());
            if (village == null) {
                return refuse(level, pos, kind, "its marker is bound to a village that is gone");
            }
        } else {
            village = registry.findVillageAt(level, pos);
            if (village == null) {
                return refuse(level, pos, kind, "it is not inside a village");
            }
        }
        if (village.getLevel() != level) {
            return refuse(level, pos, kind, "its village is in another dimension");
        }
        NavGraph graph = village.getGraph();
        if (graph == null || graph.isEmpty()) {
            RETRY.computeIfAbsent(level.dimension(), k -> new LinkedHashSet<>()).add(pos);
            retried = true;
            return null;
        }
        if (!graph.isInsideBounds(pos)) {
            return refuse(level, pos, kind, "it is outside the village's route map");
        }
        if (!village.contains(pos)) {
            return refuse(level, pos, kind, "it is not inside the village");
        }
        BlockPos tile = tile(level, graph, pos);
        if (tile == null) {
            return refuse(level, pos, kind, "there is no routable tile under it");
        }
        // only now is the marker changed: a refused stop leaves it as the player put it in
        if (binding == null) {
            ItemStack bound = stack.copy();
            boolean named = !bound.has(DataComponents.ITEM_NAME);
            if (!VillageTokenBinding.bind(bound, village)) {
                return refuse(level, pos, kind, "its marker can't be bound to the village");
            }
            frame.setItem(mark(bound, named ? "bound_named" : "bound"));
        }
        if (!VillageTokenBinding.bindFrame(frame, village)) {
            return refuse(level, pos, kind, "it is not bound to the village");
        }
        return new TransportStop(kind, village.getVillageId(), pos, tile);
    }

    /**
     * The route tile under a frame at {@code pos}: the first of its block and the two below that has a node (which
     * must be routable), else the first Towntopia would make a routable node for (a tile only a link reaches).
     */
    @Nullable
    private static BlockPos tile(ServerLevel level, NavGraph graph, BlockPos pos) {
        for (int dy = 0; dy >= -2; dy--) {
            BlockPos p = pos.above(dy);
            NavNode n = graph.nodeAt(p.getX(), p.getY(), p.getZ());
            if (n != null) {
                return n.isRoutable() ? p : null;
            }
        }
        for (int dy = 0; dy >= -2; dy--) {
            BlockPos p = pos.above(dy);
            NavNode n = LinkInstaller.probe(level, p);
            if (n != null) {
                return n.isRoutable() ? p : null;
            }
        }
        return null;
    }

    @Nullable
    private static TransportStop refuse(ServerLevel level, BlockPos pos, ResourceLocation kind, String why) {
        LOG.info("TownKit transport: refused the {} stop at {} in {}: {}", kind, pos.toShortString(),
                level.dimension().location(), why);
        return null;
    }

    /** Drops frame {@code frameId}'s stop (and the glow, when {@code frame} is loaded). */
    private static void drop(ServerLevel level, UUID frameId, @Nullable ItemFrame frame) {
        TransportStop had = StopStore.of(level).remove(frameId);
        if (had == null) {
            return;
        }
        if (frame != null) {
            glint(frame, false);
        }
        LinkInstaller.stopsChanged(level, had.villageId());
    }

    /**
     * Every stop of {@code level} whose frame's entities are loaded: dropped when the frame is gone, moved, or holds no marker of
     * the stop's live kind; re-checked when its tile changed. Resumes where the last pass stopped after 2 ms.
     */
    private static void reconcile(ServerLevel level) {
        long start = System.nanoTime();
        StopStore store = StopStore.of(level);
        List<Map.Entry<UUID, TransportStop>> stops = new ArrayList<>(store.all().entrySet());
        int from = CURSOR.getOrDefault(level.dimension(), 0);
        if (from >= stops.size()) {
            from = 0;
        }
        int i = from;
        for (; i < stops.size(); i++) {
            if (i > from && System.nanoTime() - start > BUDGET_NANOS) {
                break;
            }
            UUID id = stops.get(i).getKey();
            TransportStop s = stops.get(i).getValue();
            // a loaded chunk isn't enough: 1.21 loads a chunk's entities after it, and a frame not loaded yet must not
            // read as gone (that dropped and re-made the links on every world load or player approach)
            if (!level.areEntitiesLoaded(ChunkPos.asLong(s.framePos()))) {
                continue;
            }
            Entity e = level.getEntity(id);
            if (!(e instanceof ItemFrame frame) || frame.isRemoved() || !frame.blockPosition().equals(s.framePos())
                    || !s.kind().equals(TransportKinds.GLOBAL.kindOf(frame.getItem().getItem()))) {
                drop(level, id, e instanceof ItemFrame f ? f : null);
                continue;
            }
            TransportStop now = accept(level, frame, s.kind());
            if (now == null) {
                if (!retried) {
                    drop(level, id, frame);
                }
            } else if (!now.equals(s)) {
                for (UUID changed : store.put(id, now)) {
                    LinkInstaller.stopsChanged(level, changed);
                }
            }
        }
        CURSOR.put(level.dimension(), i >= stops.size() ? 0 : i);
    }

    /**
     * The frame's glow: Towntopia's own for its tokens; for any other marker item the same glint override, set only
     * when the item has none of its own and recorded in the item ({@link #mark}) so it can be taken off again.
     */
    private static void glint(ItemFrame frame, boolean on) {
        ItemStack stack = frame.getItem();
        if (stack.isEmpty()) {
            return;
        }
        if (ItemFrameTokenRegistry.isToken(stack)) {
            ItemFrameTokenRegistry.setRegisteredGlint(frame, on);
            return;
        }
        if (on && !stack.has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)) {
            ItemStack copy = stack.copy();
            copy.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            frame.setItem(mark(copy, "glint"));
        } else if (!on && flag(stack, "glint")) {
            ItemStack copy = stack.copy();
            copy.remove(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
            frame.setItem(clearFlag(copy, "glint"));
        }
    }

    /** Custom-data key under which TownKit records what it added to a marker item. */
    static final String MARK = "townkit:stop_marker";
    /** Towntopia's binding key in custom data ({@code VillageTokenBinding}; it has no unbind method). */
    private static final String BINDING = "dynamicvillages:town_hall_binding";

    private static ItemStack mark(ItemStack stack, String flag) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, t -> {
            CompoundTag m = t.getCompound(MARK);
            m.putBoolean(flag, true);
            t.put(MARK, m);
        });
        return stack;
    }

    private static boolean flag(ItemStack stack, String flag) {
        CustomData d = stack.get(DataComponents.CUSTOM_DATA);
        return d != null && d.contains(MARK) && d.copyTag().getCompound(MARK).getBoolean(flag);
    }

    private static ItemStack clearFlag(ItemStack stack, String flag) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, t -> {
            CompoundTag m = t.getCompound(MARK);
            m.remove(flag);
            if (m.isEmpty()) {
                t.remove(MARK);
            } else {
                t.put(MARK, m);
            }
        });
        return stack;
    }

    /** Whether TownKit changed {@code stack} as a stop marker. */
    static boolean marked(ItemStack stack) {
        CustomData d = stack.get(DataComponents.CUSTOM_DATA);
        return d != null && d.contains(MARK);
    }

    /**
     * {@code stack} without what TownKit added: the glint override it set, and the binding (and the name Towntopia's
     * binding gives) when TownKit bound it. A marker bound by someone else keeps its binding.
     */
    static ItemStack unmark(ItemStack stack) {
        ItemStack copy = stack.copy();
        CompoundTag t = copy.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        CompoundTag m = t.getCompound(MARK);
        if (m.getBoolean("glint")) {
            copy.remove(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
        }
        if (m.getBoolean("bound") || m.getBoolean("bound_named")) {
            t.remove(BINDING);
        }
        if (m.getBoolean("bound_named")) {
            copy.remove(DataComponents.ITEM_NAME);
        }
        t.remove(MARK);
        if (t.isEmpty()) {
            copy.remove(DataComponents.CUSTOM_DATA);
        } else {
            copy.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        }
        return copy;
    }
}
