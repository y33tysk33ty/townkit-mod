package townkit.core;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import townkit.api.building.BuildingDetails;
import townkit.api.building.TownKitBuildings;
import townkit.api.hooks.HookPoint;
import townkit.api.hooks.TownKitHooks;
import townkit.core.hook.Census;
import townkit.core.hook.HookManifest;
import townkit.core.hook.HookStatus;
import townkit.core.hook.Hooks;
import townkit.core.perf.PerfMonitor;

/** /townkit status | building | hooks search <words> | hooks verify | hooks census start|stop | perf [reset]. Operators only (level 2). */
public final class TownKitCommands {
    private TownKitCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("townkit").requires(s -> s.hasPermission(2))
            .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
            .then(Commands.literal("building").executes(ctx -> building(ctx.getSource())))
            .then(Commands.literal("perf")
                .executes(ctx -> lines(ctx.getSource(), PerfMonitor.report()))
                .then(Commands.literal("reset").executes(ctx -> {
                    PerfMonitor.reset();
                    ctx.getSource().sendSuccess(() -> Component.literal("TownKit performance counters reset."), false);
                    return 1;
                })))
            .then(Commands.literal("hooks")
                .then(Commands.literal("verify").executes(ctx -> verify(ctx.getSource())))
                .then(Commands.literal("census")
                    .then(Commands.literal("start").executes(ctx -> censusStart(ctx.getSource())))
                    .then(Commands.literal("stop").executes(ctx -> censusStop(ctx.getSource()))))
                .then(Commands.literal("search").then(Commands.argument("words", StringArgumentType.greedyString())
                    .executes(ctx -> search(ctx.getSource(), StringArgumentType.getString(ctx, "words")))))));
    }

    private static final java.util.concurrent.atomic.AtomicBoolean buildingFailureLogged = new java.util.concurrent.atomic.AtomicBoolean();

    private static int building(CommandSourceStack src) {
        BlockPos here = BlockPos.containing(src.getPosition());
        try {
            java.util.Optional<BuildingDetails> found = TownKitBuildings.at(src.getLevel(), here);
            if (found.isEmpty()) {
                say(src, "Not inside a Towntopia building.");
                return 0;
            }
            return lines(src, BuildingCommand.report(src.getLevel(), found.get()));
        } catch (Throwable t) {
            if (buildingFailureLogged.compareAndSet(false, true)) {
                TownKit.LOG.warn("[TownKit] /townkit building failed", t);
            }
            say(src, "couldn't read this building: " + t);
            return 0;
        }
    }

    private static int status(CommandSourceStack src) {
        int listeners = Hooks.allEntries().size();
        long off = Hooks.allEntries().stream().filter(e -> e.disabled).count();
        say(src, "TownKit " + TownKit.VERSION + " · Towntopia " + VersionGuard.installedVersion() + " · version check: " + VersionGuard.state()
            + " (" + VersionGuard.detail() + ")");
        say(src, TownKitHooks.count() + " hooks generated · " + HookStatus.hookedClasses() + " hooked classes loaded so far · "
            + listeners + " listeners attached" + (off > 0 ? " (" + off + " switched off after failures)" : ""));
        say(src, String.format(java.util.Locale.ROOT, "Startup cost: hook list %.1f ms, injecting %.1f ms (so far), version check %.1f ms",
            HookManifest.LOAD_NANOS / 1e6, HookStatus.injectNanos() / 1e6, VersionGuard.checkNanos() / 1e6));
        if (!HookStatus.skippedClasses().isEmpty()) {
            say(src, HookStatus.skippedClasses().size() + " hooked classes switched off in config/townkit-hooks.properties");
        }
        return 1;
    }

    private static int verify(CommandSourceStack src) {
        long t0 = System.nanoTime();
        HookStatus.Verification v = HookStatus.verify();
        say(src, String.format("Verified %d hooks in %.0f ms: %d live, %d missing, %d in classes that couldn't load%s.", v.checked(),
            (System.nanoTime() - t0) / 1e6, v.live(), v.missing(), v.classFailed(),
            v.clientOnly() > 0 ? " (" + v.clientOnly() + " client-only hooks not checked on a dedicated server)" : ""));
        v.missingKeys().stream().limit(8).forEach(k -> say(src, "  missing: " + k));
        v.failedClasses().stream().limit(5).forEach(k -> say(src, "  class failed: " + k));
        return v.missing() == 0 ? 1 : 0;
    }

    private static int censusStart(CommandSourceStack src) {
        if (!Census.start(src.getServer())) {
            say(src, "A hook census is already running: /townkit hooks census stop to finish it.");
            return 0;
        }
        say(src, "Counting calls on every hook. Play normally for a few minutes, then /townkit hooks census stop. "
            + "Hooked calls cost a few nanoseconds more while it runs.");
        return 1;
    }

    private static int censusStop(CommandSourceStack src) {
        try {
            Census.Result r = Census.stop(src.getServer());
            if (r == null) {
                say(src, "No hook census is running: /townkit hooks census start.");
                return 0;
            }
            say(src, String.format(java.util.Locale.ROOT, "Hook census: %,d calls over %d ticks (%.0f s), %,.0f per tick; %d of %d hooks never called.",
                r.totalCalls(), r.ticks(), r.seconds(), r.totalCalls() / (double) r.ticks(), r.neverCalled(), TownKitHooks.count()));
            r.top().forEach(l -> say(src, "  " + l));
            say(src, "Full list: " + r.file());
            return 1;
        } catch (java.io.IOException e) {
            say(src, "Census stopped but the report couldn't be written: " + e);
            return 0;
        }
    }

    private static int search(CommandSourceStack src, String words) {
        List<HookPoint> found = TownKitHooks.search(words, 15);
        if (found.isEmpty()) {
            say(src, "No hooks match '" + words + "'.");
        }
        for (HookPoint h : found) {
            src.sendSuccess(() -> Component.literal(h.key()).withStyle(ChatFormatting.AQUA)
                .append(Component.literal(" → " + h.returns().replace("com.alex.test.", "") + (h.thread() != null ? " (" + h.thread() + " thread)" : ""))
                    .withStyle(ChatFormatting.GRAY)), false);
        }
        return found.size();
    }

    private static int lines(CommandSourceStack src, List<String> lines) {
        lines.forEach(l -> say(src, l));
        return 1;
    }

    private static void say(CommandSourceStack src, String text) {
        src.sendSuccess(() -> Component.literal(text), false);
    }
}
