package townkit.test;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import townkit.api.Subscription;
import townkit.api.anim.Move;
import townkit.api.anim.TownKitAnimations;
import townkit.api.bubble.BubbleIcon;
import townkit.api.bubble.TownKitBubbles;
import townkit.api.building.TownKitBuildings;
import townkit.api.event.TownKitEvents;
import townkit.api.event.TypedEvent;
import townkit.api.hooks.TownKitHooks;
import townkit.api.nav.PathRule;
import townkit.api.nav.TownKitPaths;
import townkit.api.shop.ShopEntry;
import townkit.api.shop.TownKitShop;
import townkit.core.TownKit;
import townkit.core.anim.MoveStartHooks;
import townkit.core.bubble.BubbleHooks;
import townkit.core.building.RewrittenSites;
import townkit.core.shop.ShopHooks;

/**
 * What a hooks-off run (-Ptownkit.hooks.enabled=false) checks in the tests of behaviour that is built on Towntopia's
 * hooks: those tests assert their behaviour only when hooks are on. With hooks off {@link #skip} runs the module's probe
 * instead: the module's API calls throw nothing, report "unavailable" or hold nothing, and no hook is attached; then it
 * ends the test. The village, behaviour and path tests share the "events and hooks" probe.
 */
final class HooksOff {
    private static final String OWNER = "townkit-test-hooks-off";

    /** Which probe a skipped test runs. */
    enum Module {
        /** Events built on hooks report unavailable; listening to one gives an already disabled subscription. */
        EVENTS,
        SHOP,
        BUBBLES,
        MOVES,
        BUILDINGS,
        PATHS
    }

    private HooksOff() {
    }

    static boolean off() {
        return RewrittenSites.GLOBAL.hooksOff();
    }

    /**
     * For the top of a test of hooks-on behaviour: with hooks on, false (the test goes on); with hooks off, runs the
     * module's probe, ends the test (pass or fail by the probe) and returns true.
     */
    /** Each module's probe runs once per run (it registers things and would warn each time); the result is kept. */
    private static final java.util.Map<Module, List<String>> RESULTS = new java.util.EnumMap<>(Module.class);

    static synchronized boolean skip(GameTestHelper helper, String test, Module module) {
        if (!off()) return false;
        List<String> failures = RESULTS.get(module);
        if (failures == null) {
            Checks c = new Checks("hooks-off probe " + module);
            probe(c, module);
            failures = c.failures;
            RESULTS.put(module, failures);
        }
        if (failures.isEmpty()) helper.succeed();
        else helper.fail(test + " (hooks off, " + module + " probe): " + String.join("; ", failures));
        return true;
    }

    static void probe(Checks c, Module module) {
        try {
            switch (module) {
                case EVENTS -> events(c);
                case SHOP -> shop(c);
                case BUBBLES -> bubbles(c);
                case MOVES -> moves(c);
                case BUILDINGS -> buildings(c);
                case PATHS -> paths(c);
            }
        } catch (RuntimeException | LinkageError e) {
            TownKit.LOG.error("[TownKit test] hooks-off probe {} threw", module, e);
            c.check("nothing thrown with hooks off", false, e);
        }
    }

    /** The events that are built on Towntopia's hooks. (Polled and direct events stay available.) */
    private static void events(Checks c) {
        List<TypedEvent<?>> hookBuilt = List.of(TownKitEvents.ACTIVITY_CHANGE, TownKitEvents.PROFESSION_CHANGE, TownKitEvents.DAY_ROLLOVER,
            TownKitEvents.VILLAGE_FOUNDED, TownKitEvents.VILLAGER_REMOVED);
        List<String> available = new ArrayList<>();
        for (TypedEvent<?> e : hookBuilt) {
            if (e.available()) available.add(e.name());
        }
        c.check("hooks off: events built on hooks are unavailable", available.isEmpty(), available);
        Subscription s = TownKitEvents.ACTIVITY_CHANGE.listen(OWNER, e -> {
        });
        c.check("hooks off: listening to one gives a disabled subscription, nothing thrown", s.isDisabled(), s);
        s.remove();
    }

    /** Whatever TownKit attached for a module is not live: Towntopia's classes aren't hooked, so none of it can fire. */
    private static void notLive(Checks c, String why, List<String> attached) {
        c.check("hooks off: TownKit attached something to look at (" + why + ")", !attached.isEmpty(), attached);
        List<String> live = new ArrayList<>();
        for (String a : attached) {
            String key = a.substring(a.indexOf(' ') + 1);
            if (TownKitHooks.isLive(key)) live.add(key);
        }
        c.check("hooks off: nothing TownKit attached is live (" + why + ")", live.isEmpty(), "live " + live + " of " + attached);
    }

    private static void shop(Checks c) {
        ShopEntry entry = ShopEntry.builder(ResourceLocation.fromNamespaceAndPath(TownKit.MODID, "hooks_off_bell"), () -> net.minecraft.world.item.Items.BELL, 1)
            .build();
        Subscription s = TownKitShop.add(OWNER, entry);
        c.check("hooks off: a shop entry registers without throwing", s != null, s);
        notLive(c, "the shop stays Towntopia's", ShopHooks.attachedKeys());
        s.remove();
        c.check("hooks off: SHOP_BUYING and SHOP_BOUGHT are unavailable", !TownKitEvents.SHOP_BUYING.available() && !TownKitEvents.SHOP_BOUGHT.available(), "");
        events(c);
    }

    private static void bubbles(Checks c) {
        BubbleIcon builtIn = TownKitBubbles.builtIns().get(0);
        Subscription s = TownKitBubbles.hide(OWNER, builtIn);
        c.check("hooks off: a bubble rule registers without throwing", s != null, s);
        notLive(c, "no bubble hook can fire", BubbleHooks.attachedKeys());
        s.remove();
        c.check("hooks off: BUBBLE_SHOWING is unavailable", !TownKitEvents.BUBBLE_SHOWING.available(), "");
        events(c);
    }

    private static void moves(Checks c) {
        Subscription s = TownKitAnimations.replace(OWNER, Move.towntopia("WALK"), Move.towntopia("RUN"));
        c.check("hooks off: a move swap registers without throwing", s != null, s);
        notLive(c, "no move-start hook can fire", MoveStartHooks.attachedKeys());
        s.remove();
        c.check("hooks off: MOVE_STARTING is unavailable", !TownKitEvents.MOVE_STARTING.available(), TownKitEvents.MOVE_STARTING.available());
    }

    private static void buildings(Checks c) {
        Subscription s = TownKitBuildings.countsAs(OWNER, TestBlocks.TEST_TABLE.get(), Blocks.CRAFTING_TABLE);
        c.check("hooks off: a stand-in registers without throwing", s != null, s);
        c.check("hooks off: no Towntopia check is rewritten for it", TownKitBuildings.standIn(TestBlocks.TEST_TABLE.get()).map(r -> r.countsAt().isEmpty()).orElse(true),
            TownKitBuildings.standIn(TestBlocks.TEST_TABLE.get()));
        s.remove();
        c.check("hooks off: building events are unavailable", !TownKitEvents.BUILDING_ADDED.available() && !TownKitEvents.BUILDING_REMOVED.available(), "");
        events(c);
    }

    private static void paths(Checks c) {
        Subscription s = TownKitPaths.rule(OWNER, Blocks.STONE, PathRule.never());
        c.check("hooks off: a path rule registers without throwing", s != null, s);
        c.check("hooks off: the rule is kept for reading, and goes when removed", TownKitPaths.effective(Blocks.STONE).isPresent(), TownKitPaths.effective(Blocks.STONE));
        s.remove();
        c.check("hooks off: removing it leaves no rule", TownKitPaths.effective(Blocks.STONE).isEmpty(), TownKitPaths.effective(Blocks.STONE));
        events(c);
    }
}
