package townkit.core.work.cook;

import com.alex.test.village.villagers.cook.settings.CookSettingsSchema;
import com.alex.test.village.villagers.settings.SettingsSchema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import townkit.core.net.WorkListsPayload;

/**
 * The cook's settings page as players' screens see it. Towntopia's settings window is positional: the server writes
 * one value per row of its schema and the client reads them back by its own schema, so both must build the very same
 * rows. The server sends the dish rows ({@link #rows}); the client, and the server when it writes that player's
 * window, build the page from Towntopia's schema and those rows ({@link #schema}). Pure functions, any thread.
 */
public final class CookScreenRows {
    private static final int STOCK_MAX = 256;
    private static final int STOCK_STEP = 8;

    private CookScreenRows() {
    }

    /** The dish rows of a cook schema: its "allowed recipes" entries in order, each with its stock row's default (0: none). */
    public static List<WorkListsPayload.Row> rows(SettingsSchema schema) {
        SettingsSchema.Row allowed = schema.findRow(CookSettingsSchema.ALLOWED_RECIPES).orElse(null);
        if (allowed == null) return List.of();
        List<WorkListsPayload.Row> out = new ArrayList<>();
        for (SettingsSchema.AllowListEntry e : allowed.allowListEntries()) {
            int stock = schema.findRow(CookSettingsSchema.stockId(e.id())).map(SettingsSchema.Row::defaultInt).orElse(0);
            out.add(new WorkListsPayload.Row(e.id(), e.label(), e.description(), Math.max(0, stock)));
        }
        return List.copyOf(out);
    }

    /**
     * Towntopia's cook schema with {@code rows} as its dishes: the "allowed recipes" entries are the rows in order (a
     * Towntopia dish keeps its default switch, an added one is on by default); Towntopia's dish stock rows are replaced,
     * where the first of them was, by one stock row per row with a stock above 0 (a Towntopia dish's own row with the
     * row's stock as its default, or a new one with Towntopia's stock text), unless a row with that id is there already
     * (Towntopia keys stock rows by the item's path). Every other row is Towntopia's own.
     */
    public static SettingsSchema schema(SettingsSchema towntopia, List<WorkListsPayload.Row> rows) {
        SettingsSchema.Row allowed = towntopia.findRow(CookSettingsSchema.ALLOWED_RECIPES).orElse(null);
        Map<String, SettingsSchema.AllowListEntry> baseEntries = new HashMap<>();
        Map<String, String> stockOwner = new HashMap<>(); // Towntopia's dish stock row id -> its dish id
        if (allowed != null) {
            for (SettingsSchema.AllowListEntry e : allowed.allowListEntries()) {
                baseEntries.put(e.id(), e);
                stockOwner.put(CookSettingsSchema.stockId(e.id()), e.id());
            }
        }
        Map<String, SettingsSchema.Row> baseStock = new HashMap<>(); // dish id -> Towntopia's stock row
        String stockText = null;
        int anchorSection = -1;
        int anchor = -1;
        List<List<SettingsSchema.Row>> sectionRows = new ArrayList<>();
        Set<String> emitted = new HashSet<>();
        for (SettingsSchema.Section s : towntopia.sections()) {
            List<SettingsSchema.Row> out = new ArrayList<>();
            for (SettingsSchema.Row r : s.rows()) {
                String owner = r.kind() == SettingsSchema.RowKind.NUMERIC_STEPPER ? stockOwner.get(r.settingId()) : null;
                if (owner != null) {
                    baseStock.put(owner, r);
                    if (stockText == null) stockText = r.description();
                    if (anchorSection < 0) {
                        anchorSection = sectionRows.size();
                        anchor = out.size();
                    }
                    continue;
                }
                out.add(r == allowed ? allowRow(r, baseEntries, rows) : r);
                emitted.add(r.settingId());
            }
            sectionRows.add(out);
        }
        if (anchorSection < 0 && !sectionRows.isEmpty()) {
            anchorSection = sectionRows.size() - 1;
            anchor = sectionRows.get(anchorSection).size();
        }
        List<SettingsSchema.Row> stock = new ArrayList<>();
        for (WorkListsPayload.Row row : rows) {
            if (row.stock() <= 0) continue;
            String id = CookSettingsSchema.stockId(row.id());
            if (!emitted.add(id)) continue;
            SettingsSchema.Row own = baseStock.get(row.id());
            stock.add(own != null ? withDefault(own, row.stock())
                : SettingsSchema.Row.numericStepper(id, row.label(), row.stock(), 0, Math.max(STOCK_MAX, row.stock()), STOCK_STEP)
                    .describedAs(stockText != null ? stockText : ""));
        }
        if (!stock.isEmpty()) sectionRows.get(anchorSection).addAll(anchor, stock);
        List<SettingsSchema.Section> sections = new ArrayList<>();
        for (int i = 0; i < towntopia.sections().size(); i++) {
            sections.add(new SettingsSchema.Section(towntopia.sections().get(i).title(), sectionRows.get(i)));
        }
        return new SettingsSchema(towntopia.profession(), towntopia.tagName(), sections);
    }

    private static SettingsSchema.Row allowRow(SettingsSchema.Row r, Map<String, SettingsSchema.AllowListEntry> base,
            List<WorkListsPayload.Row> rows) {
        List<SettingsSchema.AllowListEntry> entries = new ArrayList<>();
        for (WorkListsPayload.Row row : rows) {
            SettingsSchema.AllowListEntry e = base.get(row.id());
            entries.add(e != null && e.label().equals(row.label()) && e.description().equals(row.needs()) ? e
                : new SettingsSchema.AllowListEntry(row.id(), row.label(), e == null || e.defaultAllowed(), row.needs()));
        }
        return new SettingsSchema.Row(r.kind(), r.settingId(), r.label(), r.defaultBool(), r.defaultInt(), r.minInt(), r.maxInt(),
            r.stepInt(), r.tierNames(), entries, r.description(), r.ticksAsSeconds(), r.keepsOne());
    }

    /** {@code r} with {@code stock} as its default (its maximum raised to fit). */
    private static SettingsSchema.Row withDefault(SettingsSchema.Row r, int stock) {
        if (r.defaultInt() == stock) return r;
        return new SettingsSchema.Row(r.kind(), r.settingId(), r.label(), r.defaultBool(), stock, r.minInt(), Math.max(r.maxInt(), stock),
            r.stepInt(), r.tierNames(), r.allowListEntries(), r.description(), r.ticksAsSeconds(), r.keepsOne());
    }
}
