package townkit.test;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import townkit.api.skin.SkinRef;
import townkit.core.TownKit;
import townkit.core.look.LookSync;
import townkit.core.look.VillagerLook;
import townkit.core.net.VillagerLookPayload;

/**
 * Server side of the per-villager look sync ({@link LookSync}). A NeoForge {@link FakePlayer} is never in an entity's
 * tracking list and drops every packet, so the tests swap LookSync's channel (who watches an entity, where a payload
 * goes) for a capture, and start tracking by posting NeoForge's own {@link PlayerEvent.StartTracking}. Each test runs
 * start to end inside one call on the server thread, so the swapped channel never sees another test's sends. Looks go
 * on a plain vanilla villager: LookSync works on any entity, and no test village is needed.
 */
@GameTestHolder(TownKit.MODID)
@PrefixGameTestTemplate(false)
public final class LookSyncTests {
    private static final ResourceLocation COIN = ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "test_coin");
    private static final ResourceLocation WAVE = ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "test_wave");
    private static final ResourceLocation WALK = ResourceLocation.fromNamespaceAndPath("towntopia", "walk");
    private static final ResourceLocation MARCH = ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "test_march");

    private LookSyncTests() {
    }

    private record Sent(ServerPlayer to, VillagerLookPayload payload) {
    }

    /** Records every payload; {@code watching} answers the given players for every entity. */
    private static final class Capture implements LookSync.Channel {
        final List<Sent> sent = new ArrayList<>();
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
            sent.add(new Sent(to, payload));
        }
    }

    private static FakePlayer fake(GameTestHelper helper, String name) {
        return FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.nameUUIDFromBytes(("townkit-" + name).getBytes()), name));
    }

    private static VillagerLook fullLook() {
        return new VillagerLook(COIN, true, 60, SkinRef.towntopia("merchant"), WAVE, 1, true, Map.of(WALK, MARCH));
    }

    /** A player that starts tracking a villager with a set look gets exactly one payload: that villager's look. */
    @GameTest(template = "empty", batch = "townkit_look_sync")
    public static void startTrackingSendsSnapshot(GameTestHelper helper) {
        Checks c = new Checks("startTrackingSendsSnapshot");
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 2, 1);
        Villager plain = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 3, 2, 1);
        FakePlayer player = fake(helper, "townkit_look_a");
        Capture capture = new Capture(List.of());
        LookSync.Channel old = LookSync.swapChannelForTests(capture);
        try {
            LookSync.update(villager, l -> fullLook());
            c.check("set look stored", fullLook().equals(LookSync.look(villager)), LookSync.look(villager));
            c.check("nobody watching yet: nothing sent", capture.sent.isEmpty(), capture.sent);
            NeoForge.EVENT_BUS.post(new PlayerEvent.StartTracking(player, villager));
            c.check("one payload on start tracking", capture.sent.size() == 1, capture.sent);
            if (capture.sent.size() == 1) {
                Sent s = capture.sent.get(0);
                c.check("sent to the player that started tracking", s.to() == player, s.to());
                c.check("payload names the villager", s.payload().entityId() == villager.getId(), s.payload().entityId());
                c.check("payload holds the look", fullLook().equals(s.payload().look()), s.payload().look());
            }
            capture.sent.clear();
            NeoForge.EVENT_BUS.post(new PlayerEvent.StartTracking(player, plain));
            c.check("villager without a look: nothing sent", capture.sent.isEmpty(), capture.sent);
        } finally {
            LookSync.swapChannelForTests(old);
            villager.discard();
            plain.discard();
        }
        c.finish(helper);
    }

    /** A change goes to every watcher; an update that leaves the look as it was sends nothing. */
    @GameTest(template = "empty", batch = "townkit_look_sync")
    public static void unchangedSendsNothing(GameTestHelper helper) {
        Checks c = new Checks("unchangedSendsNothing");
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 2, 1);
        FakePlayer a = fake(helper, "townkit_look_b");
        FakePlayer b = fake(helper, "townkit_look_c");
        Capture capture = new Capture(List.of(a, b));
        LookSync.Channel old = LookSync.swapChannelForTests(capture);
        try {
            LookSync.update(villager, l -> l.withBubble(COIN, false, 40));
            c.check("a change goes to both watchers", capture.sent.size() == 2, capture.sent);
            c.check("watchers get the new look", capture.sent.stream().allMatch(s -> s.payload().look().bubble() != null
                && s.payload().look().bubble().equals(COIN) && s.payload().entityId() == villager.getId()), capture.sent);
            capture.sent.clear();
            LookSync.update(villager, l -> l);
            LookSync.update(villager, l -> l.withBubble(COIN, false, 40));
            c.check("unchanged look: nothing sent", capture.sent.isEmpty(), capture.sent);
            LookSync.update(villager, l -> VillagerLook.EMPTY);
            c.check("clearing the look is sent", capture.sent.size() == 2
                && capture.sent.stream().allMatch(s -> s.payload().look().isEmpty()), capture.sent);
            c.check("cleared look reads empty", LookSync.look(villager).isEmpty(), LookSync.look(villager));
            capture.sent.clear();
            LookSync.update(villager, l -> VillagerLook.EMPTY);
            c.check("clearing an empty look: nothing sent", capture.sent.isEmpty(), capture.sent);
        } finally {
            LookSync.swapChannelForTests(old);
            villager.discard();
        }
        c.finish(helper);
    }

    /**
     * An update on an entity that already left its level (a stale reference, a late task), or that was never added
     * to one, stores and sends nothing, so LookSync can't hold on to it.
     */
    @GameTest(template = "empty", batch = "townkit_look_sync")
    public static void updateOnGoneEntityStoresNothing(GameTestHelper helper) {
        Checks c = new Checks("updateOnGoneEntityStoresNothing");
        Villager gone = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 2, 1);
        gone.discard();
        Villager never = EntityType.VILLAGER.create(helper.getLevel());
        FakePlayer player = fake(helper, "townkit_look_d");
        Capture capture = new Capture(List.of(player));
        LookSync.Channel old = LookSync.swapChannelForTests(capture);
        try {
            LookSync.update(gone, l -> l.withMove(WAVE, 1, false));
            c.check("removed entity: no look stored", LookSync.look(gone).isEmpty(), LookSync.look(gone));
            if (never == null) {
                c.check("villager created", false, "EntityType.VILLAGER.create returned null");
            } else {
                LookSync.update(never, l -> l.withMove(WAVE, 1, false));
                c.check("entity never added to the level: no look stored", LookSync.look(never).isEmpty(), LookSync.look(never));
            }
            c.check("nothing sent", capture.sent.isEmpty(), capture.sent);
        } finally {
            LookSync.swapChannelForTests(old);
        }
        c.finish(helper);
    }

    /** The look of an entity that leaves the level is dropped, so LookSync holds nothing for gone entities. */
    @GameTest(template = "empty", batch = "townkit_look_sync")
    public static void lookDroppedWhenEntityLeaves(GameTestHelper helper) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 1, 2, 1);
        Capture capture = new Capture(List.of());
        LookSync.Channel old = LookSync.swapChannelForTests(capture);
        try {
            LookSync.update(villager, l -> l.withMove(WAVE, 1, false));
        } finally {
            LookSync.swapChannelForTests(old);
        }
        if (LookSync.look(villager).isEmpty()) {
            helper.fail("look was not stored");
            return;
        }
        villager.discard();
        helper.succeedWhen(() -> {
            if (!LookSync.look(villager).isEmpty()) helper.fail("look still held after the villager left the level");
        });
    }
}
