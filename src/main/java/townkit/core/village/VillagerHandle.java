package townkit.core.village;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.VillagerBrain;
import com.alex.test.village.stats.VillagerStats;
import com.alex.test.village.villagers.core.Profession;
import java.lang.ref.WeakReference;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import townkit.api.village.Village;
import townkit.api.village.Villager;
import townkit.api.villager.VillagerDataKey;
import townkit.core.villager.DataKeys;
import townkit.core.villager.VillagerDataStore;

/**
 * {@link Villager} backed by ids. On the server every call looks the VillagerModel up again (so a removed villager reads
 * as gone): first in the village it was last seen in, then in whichever village holds it now (so the handle follows a
 * villager that moved, or whose entity lost its village link). A villager no village holds, and the client copy,
 * answer from a weak reference to the entity.
 */
public final class VillagerHandle implements Villager {
    @Nullable
    private final ServerRef server;
    /** Where the villager was last seen: a hint, checked on every call. */
    @Nullable
    private volatile UUID villageId;
    private final UUID npcId;
    @Nullable
    private final WeakReference<NpcEntity> body;

    public VillagerHandle(@Nullable MinecraftServer server, @Nullable UUID villageId, UUID npcId, @Nullable NpcEntity body) {
        this.server = server == null ? null : new ServerRef(server);
        this.villageId = villageId;
        this.npcId = npcId;
        this.body = body == null ? null : new WeakReference<>(body);
    }

    /** The handle for an entity; its village link is the first place to look (none on the client). */
    public static VillagerHandle of(NpcEntity npc) {
        if (npc.level().isClientSide()) return new VillagerHandle(null, null, npc.getNpcId(), npc);
        var link = npc.getVillageLink();
        return new VillagerHandle(npc.level().getServer(), link == null ? null : link.villageId(), npc.getNpcId(), npc);
    }

    /** The handle for a villager Towntopia holds (e.g. a brain's own villager), loaded or not. */
    public static VillagerHandle of(VillagerModel model) {
        NpcEntity npc = model.puppet();
        if (npc != null && !npc.level().isClientSide()) return of(npc);
        return new VillagerHandle(ServerLifecycleHooks.getCurrentServer(), null, model.npcId, null);
    }

    /** The village that holds this villager now; null when none does (or on the client). */
    @Nullable
    private VillageManager manager() {
        MinecraftServer s = this.server == null ? null : this.server.live();
        if (s == null) return null;
        VillageManagerRegistry registry = VillageManagerRegistry.get(s);
        UUID hint = this.villageId;
        VillageManager m = hint == null ? null : registry.get(hint);
        if (m != null && m.getModel(this.npcId) != null) return m;
        for (VillageManager other : registry.all()) {
            if (other.getModel(this.npcId) != null) {
                this.villageId = other.getVillageId();
                return other;
            }
        }
        return null;
    }

    @Nullable
    public VillagerModel model() {
        VillageManager m = this.manager();
        return m == null ? null : m.getModel(this.npcId);
    }

    @Nullable
    public NpcEntity npc() {
        VillagerModel model = this.model();
        NpcEntity npc = model != null ? model.puppet() : this.body == null ? null : this.body.get();
        return npc == null || npc.isRemoved() ? null : npc;
    }

    @Nullable
    private VillagerBrain brain() {
        VillagerModel model = this.model();
        return model == null ? null : model.brain();
    }

    @Override
    public UUID id() {
        return this.npcId;
    }

    @Override
    public boolean exists() {
        return this.model() != null || this.npc() != null;
    }

    @Override
    public ResourceLocation profession() {
        NpcEntity npc = this.npc();
        VillagerBrain brain = this.brain();
        Profession p = npc != null ? npc.getProfession() : brain != null ? brain.profession() : null;
        return Ids.profession(p == null ? "none" : p.name());
    }

    @Override
    public String activity() {
        VillagerBrain brain = this.brain();
        Activity a = brain == null ? null : brain.currentActivity();
        return a == null ? "none" : a.name();
    }

    @Override
    public float hunger() {
        VillagerBrain brain = this.brain();
        VillagerStats needs = brain == null ? null : brain.needs();
        return needs == null ? 0F : needs.hunger;
    }

    @Override
    public float wellbeing() {
        VillagerBrain brain = this.brain();
        VillagerStats needs = brain == null ? null : brain.needs();
        return needs == null ? 0F : needs.wellbeing;
    }

    @Override
    public boolean sad() {
        NpcEntity npc = this.npc();
        return npc != null && npc.isSad();
    }

    @Override
    public boolean moving() {
        VillagerModel model = this.model();
        return model != null && model.isMoving();
    }

    @Override
    public boolean loaded() {
        return this.npc() != null;
    }

    @Override
    public Optional<BlockPos> position() {
        NpcEntity npc = this.npc();
        return npc == null ? Optional.empty() : Optional.of(npc.blockPosition());
    }

    @Override
    public Optional<Village> village() {
        VillageManager m = this.manager();
        if (m == null || m.getModel(this.npcId) == null) return Optional.empty();
        return Optional.ofNullable(VillageHandle.of(m));
    }

    @Override
    public Optional<Entity> entity() {
        return Optional.ofNullable(this.npc());
    }

    /** The needs of a loaded villager (see {@link Villager#addWellbeing(float)} for why only loaded); else null. */
    @Nullable
    private VillagerStats loadedNeeds() {
        if (this.npc() == null) return null;
        VillagerBrain brain = this.brain();
        return brain == null ? null : brain.needs();
    }

    @Override
    public boolean addWellbeing(float delta) {
        VillagerStats needs = this.loadedNeeds();
        if (needs == null) return false;
        needs.addWellbeing(delta);
        return true;
    }

    /** TownKit's data store on this handle's server while it runs; null on the client or after it stopped. */
    @Nullable
    private VillagerDataStore store() {
        MinecraftServer s = this.server == null ? null : this.server.live();
        return s == null ? null : VillagerDataStore.of(s);
    }

    @Override
    public <T> Optional<T> data(VillagerDataKey<T> key) {
        VillagerDataStore store = this.store();
        return store == null ? Optional.empty() : DataKeys.decode(key, store.get(this.npcId, key.id()));
    }

    @Override
    public <T> boolean setData(VillagerDataKey<T> key, T value) {
        VillagerDataStore store = this.store();
        if (store == null) return false;
        var tag = DataKeys.encode(key, value);
        if (tag.isEmpty()) throw new IllegalArgumentException("Villager data for " + key.id() + " can't be encoded: " + value);
        store.put(this.npcId, key.id(), tag.get());
        return true;
    }

    @Override
    public boolean removeData(VillagerDataKey<?> key) {
        VillagerDataStore store = this.store();
        return store != null && store.remove(this.npcId, key.id());
    }

    @Override
    public boolean addHunger(float delta) {
        VillagerStats needs = this.loadedNeeds();
        if (needs == null) return false;
        needs.hunger += delta;
        needs.clamp();
        return true;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VillagerHandle h && h.npcId.equals(this.npcId);
    }

    @Override
    public int hashCode() {
        return this.npcId.hashCode();
    }

    @Override
    public String toString() {
        return "Villager[" + this.npcId + (this.exists() ? "" : ", gone") + "]";
    }
}
