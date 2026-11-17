package townkit.core.work.cook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alex.test.village.villagers.cook.settings.CookSettingsSchema;
import com.alex.test.village.villagers.settings.SettingsSchema;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import townkit.core.net.WorkListsPayload;

/** The cook's settings page as a player's screen builds it: Towntopia's schema plus the rows the server sent. */
class CookScreenRowsTest {
    private static final SettingsSchema TOWNTOPIA = CookSettingsSchema.SCHEMA;

    private static List<String> rowIds(SettingsSchema s) {
        List<String> out = new ArrayList<>();
        for (SettingsSchema.Section sec : s.sections()) {
            for (SettingsSchema.Row r : sec.rows()) out.add(r.settingId());
        }
        return out;
    }

    private static SettingsSchema.Row allowed(SettingsSchema s) {
        return s.findRow(CookSettingsSchema.ALLOWED_RECIPES).orElseThrow();
    }

    @Test
    void towntopiasOwnRowsGiveTowntopiasSchema() {
        List<WorkListsPayload.Row> rows = CookScreenRows.rows(TOWNTOPIA);
        assertEquals(allowed(TOWNTOPIA).allowListEntries().size(), rows.size());
        WorkListsPayload.Row bread = rows.stream().filter(r -> r.id().equals("minecraft:bread")).findFirst().orElseThrow();
        assertEquals("Bread", bread.label());
        assertEquals(TOWNTOPIA.findRow("stock_bread").orElseThrow().defaultInt(), bread.stock());
        assertEquals(TOWNTOPIA, CookScreenRows.schema(TOWNTOPIA, rows), "same rows, same page");
    }

    @Test
    void addedChangedAndRemovedDishes() {
        List<WorkListsPayload.Row> rows = new ArrayList<>();
        for (WorkListsPayload.Row r : CookScreenRows.rows(TOWNTOPIA)) {
            if (r.id().equals("minecraft:cake")) continue; // removed
            if (r.id().equals("minecraft:bread")) r = new WorkListsPayload.Row(r.id(), r.label(), "Needs 2 Wheat.\nOFF: never makes it.", 24);
            rows.add(r);
        }
        rows.add(new WorkListsPayload.Row("mymod:stew", "Stew", "Needs 1 Wheat.\nOFF: never makes it.", 20));
        rows.add(new WorkListsPayload.Row("mymod:ore_only", "No stock", "", 0));
        SettingsSchema s = CookScreenRows.schema(TOWNTOPIA, rows);

        List<String> ids = allowed(s).entryIds();
        assertFalse(ids.contains("minecraft:cake"));
        assertEquals("mymod:stew", ids.get(ids.size() - 2), "added dishes after Towntopia's");
        SettingsSchema.AllowListEntry bread = allowed(s).allowListEntries().stream().filter(e -> e.id().equals("minecraft:bread"))
            .findFirst().orElseThrow();
        assertEquals("Needs 2 Wheat.\nOFF: never makes it.", bread.description());
        assertTrue(allowed(s).allowListEntries().stream().filter(e -> e.id().equals("mymod:stew")).findFirst().orElseThrow().defaultAllowed());

        assertTrue(s.findRow("stock_cake").isEmpty(), "a removed dish loses its stock row");
        assertEquals(24, s.findRow("stock_bread").orElseThrow().defaultInt());
        SettingsSchema.Row stew = s.findRow("stock_stew").orElseThrow();
        assertEquals(20, stew.defaultInt());
        assertEquals("Stew", stew.label());
        assertEquals(TOWNTOPIA.findRow("stock_bread").orElseThrow().description(), stew.description(), "Towntopia's stock text");
        assertTrue(s.findRow("stock_ore_only").isEmpty(), "stock 0: no stock row");
        List<String> all = rowIds(s);
        assertEquals(all.indexOf("stock_dried_kelp") + 1, all.indexOf("stock_stew"), "after Towntopia's dish stock rows");
        assertEquals(TOWNTOPIA.tagName(), s.tagName(), "saved under Towntopia's tag");
        assertSame(TOWNTOPIA.profession(), s.profession());
        // the other rows are Towntopia's own
        assertSame(TOWNTOPIA.findRow(CookSettingsSchema.GOLD_RESERVE).orElseThrow(), s.findRow(CookSettingsSchema.GOLD_RESERVE).orElseThrow());
    }

    @Test
    void sharedStockRowIdGoesToTheFirst() {
        // Towntopia keys stock rows by the item's path: mymod:bread can't have a row of its own beside minecraft:bread
        List<WorkListsPayload.Row> rows = new ArrayList<>(CookScreenRows.rows(TOWNTOPIA));
        rows.add(new WorkListsPayload.Row("mymod:bread", "Mod Bread", "", 12));
        SettingsSchema s = CookScreenRows.schema(TOWNTOPIA, rows);
        assertEquals(TOWNTOPIA.findRow("stock_bread").orElseThrow(), s.findRow("stock_bread").orElseThrow());
        assertEquals(1, rowIds(s).stream().filter("stock_bread"::equals).count());
        // with Towntopia's bread gone, the row is the mod's
        rows.removeIf(r -> r.id().equals("minecraft:bread"));
        SettingsSchema s2 = CookScreenRows.schema(TOWNTOPIA, rows);
        assertEquals("Mod Bread", s2.findRow("stock_bread").orElseThrow().label());
        assertEquals(12, s2.findRow("stock_bread").orElseThrow().defaultInt());
    }
}
