package townkit.core.nav;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;
import townkit.api.Subscription;
import townkit.api.nav.TransportKind;
import townkit.api.nav.TransportLink;
import townkit.api.spi.TownKitTransportView;
import townkit.api.village.Village;

/**
 * Registered transport kinds and the subscriptions that enable them ({@link townkit.api.nav.TownKitTransport}). A
 * kind is live while it is registered and enabled at least once; only live kinds have stops (their markers) and
 * links. TownKit registers its ready-made kinds ({@link BubbleElevator}, from {@link CorePaths}) and enables none. Any thread; changes reach {@link LinkInstaller}
 * on the server thread.
 */
public final class TransportKinds implements TownKitTransportView {
    public static final TransportKinds GLOBAL = new TransportKinds();
    private static final Logger LOG = LogUtils.getLogger();

    private record KindEntry(String owner, TransportKind kind) {
    }

    private record Enable(String owner, ResourceLocation kind, Item marker) {
    }

    private final Map<ResourceLocation, KindEntry> kinds = new LinkedHashMap<>();
    private final List<Enable> enables = new ArrayList<>();
    private final Set<Item> warnedMarkers = new HashSet<>();
    /** Marker item -> live kind id (rebuilt on every change, never mutated). */
    private volatile Map<Item, ResourceLocation> markers = Map.of();
    /** Live kinds by id (rebuilt on every change, never mutated). */
    private volatile Map<ResourceLocation, TransportKind> live = Map.of();

    TransportKinds() {
    }

    @Override
    public Subscription registerKind(String owner, TransportKind kind) {
        checkOwner(owner);
        Objects.requireNonNull(kind, "kind");
        ResourceLocation id = Objects.requireNonNull(kind.id(), "kind.id()");
        KindEntry entry = new KindEntry(owner, kind);
        synchronized (this) {
            KindEntry had = kinds.get(id);
            if (had != null) {
                throw new IllegalArgumentException("transport kind " + id + " is already registered by " + had.owner());
            }
            kinds.put(id, entry);
            rebuild();
        }
        LinkInstaller.kindsChanged();
        return new Sub(() -> {
            synchronized (this) {
                if (kinds.get(id) == entry) {
                    kinds.remove(id);
                }
                rebuild();
            }
            LinkInstaller.kindsChanged();
        });
    }

    @Override
    public Subscription enable(String owner, ResourceLocation kindId, Item marker) {
        checkOwner(owner);
        Objects.requireNonNull(kindId, "kindId");
        Objects.requireNonNull(marker, "marker");
        Enable e = new Enable(owner, kindId, marker);
        synchronized (this) {
            enables.add(e);
            rebuild();
        }
        LinkInstaller.attach();
        RideEngine.attach();
        LinkInstaller.kindsChanged();
        return new Sub(() -> {
            synchronized (this) {
                enables.remove(e);
                rebuild();
            }
            LinkInstaller.kindsChanged();
            RideEngine.detachIfUnused();
        });
    }

    /**
     * Registers one of TownKit's ready-made kinds for good, at mod construction, disabled (no enable yet, so nothing to
     * recompute and nothing of Towntopia's is touched).
     */
    synchronized void builtIn(String owner, TransportKind kind) {
        if (kinds.putIfAbsent(kind.id(), new KindEntry(owner, kind)) == null) {
            rebuild();
        }
    }

    /** Whether any kind is enabled (by any subscription, registered or not). */
    synchronized boolean anyEnabled() {
        return !enables.isEmpty();
    }

    @Override
    public List<TransportLink> links(Village village) {
        return LinkInstaller.links(Objects.requireNonNull(village, "village").id());
    }

    /** The live kind {@code item} marks a stop of, or null. */
    @Nullable
    public ResourceLocation kindOf(Item item) {
        return markers.get(item);
    }

    /** The kind {@code id} while it is live (registered and enabled), else null. */
    @Nullable
    public TransportKind live(ResourceLocation id) {
        return live.get(id);
    }

    private static void checkOwner(String owner) {
        Objects.requireNonNull(owner, "owner");
        if (owner.isBlank()) {
            throw new IllegalArgumentException("owner is blank");
        }
    }

    /** Under the lock: the live kinds and markers again. An item enabled for two kinds stays the first one's. */
    private void rebuild() {
        Map<ResourceLocation, TransportKind> l = new HashMap<>();
        Map<Item, ResourceLocation> m = new HashMap<>();
        for (Enable e : enables) {
            KindEntry k = kinds.get(e.kind());
            if (k == null) {
                continue;
            }
            l.put(e.kind(), k.kind());
            ResourceLocation had = m.putIfAbsent(e.marker(), e.kind());
            if (had != null && !had.equals(e.kind()) && warnedMarkers.add(e.marker())) {
                LOG.warn("TownKit transport: {} enabled {} with a marker item that already marks {} stops; it stays a {} "
                        + "marker", e.owner(), e.kind(), had, had);
            }
        }
        live = Map.copyOf(l);
        markers = Map.copyOf(m);
    }

    /** A subscription that runs {@code onRemove} once. */
    private static final class Sub implements Subscription {
        private final Runnable onRemove;
        private final AtomicBoolean removed = new AtomicBoolean();

        Sub(Runnable onRemove) {
            this.onRemove = onRemove;
        }

        @Override
        public void remove() {
            if (removed.compareAndSet(false, true)) {
                onRemove.run();
            }
        }

        @Override
        public boolean isDisabled() {
            return false;
        }
    }
}
