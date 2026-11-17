package townkit.core.hook;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The generated list of hooks (townkit/hooks.tsv, format described in hookgen's HookGen), loaded once. */
public final class HookManifest {
    private static final String ROOT = "com/alex/test/";
    /** How long reading the manifest took at startup. */
    public static final long LOAD_NANOS;
    private static final HookManifest INSTANCE;

    static {
        long t0 = System.nanoTime();
        INSTANCE = load();
        LOAD_NANOS = System.nanoTime() - t0;
    }

    public final HookInfo[] hooks;
    public final Map<String, HookInfo> byKey;
    /** Hooks per Towntopia class (internal name), for the injector. */
    public final Map<String, List<HookInfo>> byOwner;
    /** Fingerprint of the Towntopia classes the hooks were generated from (see VersionGuard). */
    public final String fingerprint;
    public final String generatedFrom;

    private HookManifest(HookInfo[] hooks, Map<String, List<HookInfo>> byOwner, String fingerprint, String generatedFrom) {
        this.hooks = hooks;
        this.byOwner = java.util.Collections.unmodifiableMap(byOwner);
        this.fingerprint = fingerprint;
        this.generatedFrom = generatedFrom;
        Map<String, HookInfo> map = new HashMap<>(hooks.length * 2);
        for (HookInfo h : hooks) {
            map.put(h.key(), h);
        }
        this.byKey = java.util.Collections.unmodifiableMap(map);
    }

    public static HookManifest get() {
        return INSTANCE;
    }

    private static HookManifest load() {
        try (InputStream in = HookManifest.class.getResourceAsStream("/townkit/hooks.tsv")) {
            if (in == null) {
                return new HookManifest(new HookInfo[0], Map.of(), "", "missing");
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
            String fingerprint = "";
            String from = "?";
            HookInfo[] hooks = new HookInfo[0];
            Map<String, List<HookInfo>> byOwner = new HashMap<>();
            int id = 0;
            String owner = null;
            String keyPrefix = null;
            boolean client = false;
            List<HookInfo> ofOwner = null;
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\t", -1);
                switch (f[0]) {
                    case "townkit-hooks" -> {
                    }
                    case "from" -> from = f[1];
                    case "fingerprint" -> fingerprint = f[1];
                    case "count" -> hooks = new HookInfo[Integer.parseInt(f[1])];
                    case "C" -> {
                        owner = f[1];
                        String rel = owner.startsWith(ROOT) ? owner.substring(ROOT.length()) : owner;
                        keyPrefix = rel.replace('/', '.') + "#";
                        client = f.length > 2 && f[2].equals("client");
                        ofOwner = new ArrayList<>(4);
                        byOwner.put(owner, ofOwner);
                    }
                    default -> {
                        String flags = f[0];
                        String access = switch (flags.charAt(0)) {
                            case 'P' -> "public";
                            case 'R' -> "protected";
                            case 'V' -> "private";
                            default -> "package";
                        };
                        HookInfo h = new HookInfo(id, keyPrefix + f[3], owner, f[1], f[2], flags.indexOf('s') > 0, access,
                            f.length > 4 ? f[4] : null, flags.indexOf('c') > 0, client);
                        hooks[id++] = h;
                        ofOwner.add(h);
                    }
                }
            }
            if (id != hooks.length) {
                throw new IllegalStateException("manifest lists " + hooks.length + " hooks but holds " + id);
            }
            return new HookManifest(hooks, byOwner, fingerprint, from);
        } catch (Exception e) {
            throw new IllegalStateException("TownKit hook manifest is unreadable", e);
        }
    }
}
