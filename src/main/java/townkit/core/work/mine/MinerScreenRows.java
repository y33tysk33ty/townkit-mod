package townkit.core.work.mine;

import com.alex.test.village.villagers.miner.settings.MinerSettingsSchema;
import com.alex.test.village.villagers.settings.SettingsSchema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import townkit.core.net.WorkListsPayload;

/**
 * The miner's settings page as players' screens see it, the way {@code CookScreenRows} does the cook's: the server sends
 * the "wanted ores" rows ({@link #rows}); the client, and the server when it writes that player's window, build the page
 * from Towntopia's schema and those rows ({@link #schema}). Pure functions, any thread.
 */
public final class MinerScreenRows {
    private MinerScreenRows() {
    }

    /** The ore rows of a miner schema: its "wanted ores" entries in order ({@code stock} is not used: 0). */
    public static List<WorkListsPayload.Row> rows(SettingsSchema schema) {
        SettingsSchema.Row wanted = schema.findRow(MinerSettingsSchema.WANTED_ORES).orElse(null);
        if (wanted == null) return List.of();
        List<WorkListsPayload.Row> out = new ArrayList<>();
        for (SettingsSchema.AllowListEntry e : wanted.allowListEntries()) {
            out.add(new WorkListsPayload.Row(e.id(), e.label(), e.description(), 0));
        }
        return List.copyOf(out);
    }

    /**
     * Towntopia's miner schema with {@code rows} as its wanted ores: the "wanted ores" entries are the rows in order (a
     * Towntopia ore keeps its default switch, an added one is off by default). Every other row is Towntopia's own.
     */
    public static SettingsSchema schema(SettingsSchema towntopia, List<WorkListsPayload.Row> rows) {
        SettingsSchema.Row wanted = towntopia.findRow(MinerSettingsSchema.WANTED_ORES).orElse(null);
        Map<String, SettingsSchema.AllowListEntry> base = new HashMap<>();
        if (wanted != null) {
            for (SettingsSchema.AllowListEntry e : wanted.allowListEntries()) base.put(e.id(), e);
        }
        List<SettingsSchema.Section> sections = new ArrayList<>();
        for (SettingsSchema.Section s : towntopia.sections()) {
            List<SettingsSchema.Row> out = new ArrayList<>();
            for (SettingsSchema.Row r : s.rows()) out.add(r == wanted ? wantedRow(r, base, rows) : r);
            sections.add(new SettingsSchema.Section(s.title(), out));
        }
        return new SettingsSchema(towntopia.profession(), towntopia.tagName(), sections);
    }

    private static SettingsSchema.Row wantedRow(SettingsSchema.Row r, Map<String, SettingsSchema.AllowListEntry> base,
            List<WorkListsPayload.Row> rows) {
        List<SettingsSchema.AllowListEntry> entries = new ArrayList<>();
        for (WorkListsPayload.Row row : rows) {
            SettingsSchema.AllowListEntry e = base.get(row.id());
            entries.add(e != null && e.label().equals(row.label()) && e.description().equals(row.needs()) ? e
                : new SettingsSchema.AllowListEntry(row.id(), row.label(), e != null && e.defaultAllowed(), row.needs()));
        }
        return new SettingsSchema.Row(r.kind(), r.settingId(), r.label(), r.defaultBool(), r.defaultInt(), r.minInt(), r.maxInt(),
            r.stepInt(), r.tierNames(), entries, r.description(), r.ticksAsSeconds(), r.keepsOne());
    }
}
