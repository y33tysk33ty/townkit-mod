package townkit.core.work.mine;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.miner.OreBlocks;
import com.alex.test.village.villagers.miner.OreHeights;
import com.alex.test.village.villagers.miner.settings.MinerSettingsSchema;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.alex.test.village.villagers.settings.SettingsSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import townkit.api.hooks.HookCall;
import townkit.api.hooks.HookListener;
import townkit.api.work.OreEntry;
import townkit.core.TownKit;
import townkit.core.building.RewrittenSites;
import townkit.core.hook.HookInfo;
import townkit.core.hook.HookManifest;
import townkit.core.hook.Hooks;
import townkit.core.work.WorkLog;
import townkit.core.work.WorkScreens;
import townkit.core.work.WorkSite;
import townkit.core.work.WorkSites;
import townkit.core.work.forge.ForgeHooks;

/**
 * Switches the miner's ore overlay on for Towntopia: the hooks it needs beside the two class-load rewrites
 * ({@link MineBridges}), each attached only while it has something to do, so a world without an addon ore runs
 * Towntopia's miner and druid with none of them:
 * <ul>
 *   <li>{@link #FOR_PROFESSION} after, while an addon ore exists: the settings registry answers the merged miner page
 *   (a "wanted ores" row per addon ore); while a player's settings window is written, the page that player's client
 *   builds ({@link WorkScreens}); never on a client's main thread (its screens: {@code WorkScreensHooks}).</li>
 *   <li>{@link #YIELD_SOURCE} after, while an ore has lucky rolls: when Towntopia's roll turned the stone the miner dug
 *   into an ore, an addon ore may take its place ({@link MiningOverlay.View#pickLucky}). The miner's live dig and
 *   its away catch-up both call it.</li>
 *   <li>{@link #REGROWN_ROCK} after, while an ore has druid placements: when the druid's regrown rock came out as one
 *   of Towntopia's seven ores, an addon ore may take its place. The druid's emerald and ancient debris, and the miner,
 *   are not touched.</li>
 *   <li>{@link #WANTED_ORES} and {@link #FIT} after, while an ore has depths: the villager's switched-on addon ores are
 *   remembered with the wanted-ores set Towntopia built, and its fit of a mine's depth ({@code OreHeights.fit}) grows
 *   by 1 for each of them whose depths hold the mine's Y. {@link MineBridges#onWantedEmpty} lets a villager that wants
 *   only addon ores sort the mines at all.</li>
 * </ul>
 * The ore check ({@link MineBridges#onIsOre}) is on while the rewritten rows are in reach; the rest doesn't need it.
 */
public final class MineHooks {
    static final String FOR_PROFESSION = "village.villagers.settings.SettingsSchemaRegistry#forProfession(Profession)";
    static final String YIELD_SOURCE = "village.villagers.miner.behavior.MinerActivity#yieldSource(BlockState,int,int,RandomSource)";
    static final String REGROWN_ROCK = "village.villagers.druid.DruidBalance#regrownRock(int,int,BlockState,RandomSource)";
    static final String WANTED_ORES = "village.villagers.miner.behavior.MinerActivity#wantedOres(NpcEntity)";
    static final String FIT = "village.villagers.miner.OreHeights#fit(Collection,int)";
    private static final String BRIDGES = "townkit/core/work/mine/MineBridges";

    /** Attached hooks by key and side ("key|after"). */
    private static final Map<String, Hooks.Entry> ATTACHED = new LinkedHashMap<>();
    @Nullable
    private static Boolean reached;
    @Nullable
    private static Block[] towntopiaStone;
    @Nullable
    private static Block[] towntopiaDeepslate;

    private MineHooks() {
    }

    /** Attaches what the overlay needs now and detaches the rest. Call after any change of the ore list. */
    public static synchronized void refresh() {
        MiningOverlay.View v = MiningOverlay.GLOBAL.view();
        boolean any = v.touched();
        // hooks switched off: no class is woven, so a hook would never be called; none is attached
        boolean live = !RewrittenSites.GLOBAL.hooksOff();
        set(FOR_PROFESSION, live && any, MineHooks::afterForProfession);
        set(YIELD_SOURCE, live && v.lucky().length > 0, MineHooks::afterYield);
        set(REGROWN_ROCK, live && v.druid().length > 0, MineHooks::afterRegrown);
        set(WANTED_ORES, live && v.steer().length > 0, MineHooks::afterWanted);
        set(FIT, live && v.steer().length > 0, MineHooks::afterFit);
        MineBridges.remember(null, null);
        MineBridges.active = any && reach() ? v : null;
        WorkScreens.refresh();
    }

    /** Whether the ore check is on for Towntopia's miner right now. */
    public static boolean checkOn() {
        return MineBridges.active != null;
    }

    /** The hook keys attached right now (for tests). */
    public static synchronized List<String> attachedKeys() {
        List<String> out = new ArrayList<>();
        for (Hooks.Entry e : ATTACHED.values()) out.add(e.hook.key());
        return out;
    }

    private static void set(String key, boolean wanted, HookListener listener) {
        Hooks.Entry e = ATTACHED.get(key);
        if (wanted && e == null) {
            HookInfo h = HookManifest.get().byKey.get(key);
            if (h == null) {
                WorkLog.GLOBAL.once("mine-hook|" + key, "[" + MiningOverlay.KIND + "] can't find the hook " + key + "; that part of the ore overlay is off");
                return;
            }
            ATTACHED.put(key, Hooks.add(h, TownKit.MODID, listener, true));
        } else if (!wanted && e != null) {
            Hooks.remove(e);
            ATTACHED.remove(key);
        }
    }

    /**
     * Whether TownKit reaches the miner's ore check: every REWRITE row naming {@link MineBridges} was rewritten (the class
     * is loaded for that, without initialising it). Known once a server runs; logged once when it isn't.
     */
    static synchronized boolean reach() {
        if (reached != null) return reached;
        if (ServerLifecycleHooks.getCurrentServer() == null) return false;
        List<String> missing = new ArrayList<>();
        if (RewrittenSites.GLOBAL.hooksOff()) {
            missing.add("TownKit's hooks are off");
        } else {
            try {
                missing.addAll(ForgeHooks.missingRows(MineHooks::mineRow));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                missing.add(e.toString());
            }
        }
        reached = missing.isEmpty();
        if (!reached) {
            TownKit.LOG.warn("[TownKit] ores: Towntopia's miner is out of TownKit's reach ({}); addon ore blocks don't count as ore for it "
                + "and a villager that wants only an addon ore sorts no mines", missing.size() > 5 ? missing.subList(0, 5) + " and "
                + (missing.size() - 5) + " more" : missing);
        }
        return reached;
    }

    private static boolean mineRow(WorkSites sites, int id) {
        return sites.kind(id).style != WorkSite.Style.AUDIT_ONLY && sites.bridgeOwner(id).equals(BRIDGES);
    }

    // --- settings -------------------------------------------------------------------------------------------------------

    private static void afterForProfession(HookCall call) {
        // a client's own screens (its main thread) show the rows its server sent: WorkScreensHooks answers there
        if (WorkScreens.onClientThread()) return;
        if (call.returnValue() instanceof Optional<?> o && o.isPresent() && o.get() instanceof SettingsSchema s
                && s.profession() == Profession.MINER) {
            // a settings window being written for a player: the page that player's client builds
            SettingsSchema page = WorkScreens.writing();
            SettingsSchema merged = page != null ? page : MiningOverlay.GLOBAL.schemaFor(s);
            if (merged != s) call.setReturnValue(Optional.of(merged));
        }
    }

    // --- the miner's lucky rolls and the druid's regrown rock ----------------------------------------------------------

    private static void afterYield(HookCall call) {
        MiningOverlay.View v = MiningOverlay.GLOBAL.view();
        Object[] args = call.args();
        if (v.lucky().length == 0 || !(args[0] instanceof BlockState input) || !(args[1] instanceof Integer y)
                || !(args[3] instanceof RandomSource random) || !(call.returnValue() instanceof BlockState out) || out == input) {
            return; // not a roll that produced an ore: Towntopia's own answer
        }
        OreEntry ore = v.pickLucky(MiningOverlay.towntopiaTotal(y), y, random.nextDouble());
        if (ore != null) call.setReturnValue(v.blockAt(ore, y).defaultBlockState());
    }

    private static void afterRegrown(HookCall call) {
        MiningOverlay.View v = MiningOverlay.GLOBAL.view();
        Object[] args = call.args();
        if (v.druid().length == 0 || !(args[1] instanceof Integer y) || !(args[2] instanceof BlockState input)
                || !(args[3] instanceof RandomSource random) || !(call.returnValue() instanceof BlockState out) || out == input) {
            return;
        }
        loadTowntopiaOres();
        boolean deepslate = contains(towntopiaDeepslate, out.getBlock());
        if (!deepslate && !contains(towntopiaStone, out.getBlock())) return; // emerald, ancient debris: not one of the seven
        OreEntry ore = v.pickDruid(MiningOverlay.towntopiaTotal(y), y, random.nextDouble());
        if (ore == null) return;
        Block block = deepslate && ore.deepslate() != null ? ore.deepslate() : ore.stone();
        call.setReturnValue(block.defaultBlockState());
    }

    private static synchronized void loadTowntopiaOres() {
        if (towntopiaStone != null) return;
        OreHeights.Ore[] ores = OreHeights.Ore.values();
        Block[] stone = new Block[ores.length];
        Block[] deepslate = new Block[ores.length];
        for (int i = 0; i < ores.length; i++) {
            stone[i] = OreBlocks.stone(ores[i]);
            deepslate[i] = OreBlocks.deepslate(ores[i]);
        }
        towntopiaDeepslate = deepslate;
        towntopiaStone = stone;
    }

    private static boolean contains(Block[] blocks, Block block) {
        for (int i = 0; i < blocks.length; i++) {
            if (blocks[i] == block) return true;
        }
        return false;
    }

    // --- the mine choice ------------------------------------------------------------------------------------------------

    /** Remembers which addon ores the villager has switched on, against the wanted-ores set Towntopia just built. */
    private static void afterWanted(HookCall call) {
        MiningOverlay.View v = MiningOverlay.GLOBAL.view();
        OreEntry[] steer = v.steer();
        if (steer.length == 0 || !(call.args()[0] instanceof NpcEntity npc) || call.returnValue() == null) {
            MineBridges.remember(null, null);
            return;
        }
        ProfessionSettings settings = ProfessionSettings.of(npc, MiningOverlay.GLOBAL.schema());
        List<OreEntry> wished = null;
        for (int i = 0; i < steer.length; i++) {
            if (settings.isAllowed(MinerSettingsSchema.WANTED_ORES, steer[i].id().toString())) {
                if (wished == null) wished = new ArrayList<>(steer.length);
                wished.add(steer[i]);
            }
        }
        MineBridges.remember(call.returnValue(), wished == null ? null : wished.toArray(new OreEntry[0]));
    }

    /** Towntopia's fit of a mine's depth to the wanted ores, plus the villager's wanted addon ores' (when its set is the last one asked). */
    private static void afterFit(HookCall call) {
        Object[] args = call.args();
        OreEntry[] wished = MineBridges.wishes(args[0]);
        if (wished == null || !(args[1] instanceof Integer y) || !(call.returnValue() instanceof Double fit)) return;
        call.setReturnValue(fit + MiningOverlay.View.fit(wished, y));
    }
}
