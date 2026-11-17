package townkit.core.work.mine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alex.test.village.villagers.miner.settings.MinerSettingsSchema;
import com.alex.test.village.villagers.settings.SettingsSchema;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import townkit.core.net.WorkListsPayload;

/** The miner's settings page as a player's screen builds it: Towntopia's schema plus the ore rows the server sent. */
class MinerScreenRowsTest {
    private static final SettingsSchema TOWNTOPIA = MinerSettingsSchema.SCHEMA;

    private static SettingsSchema.Row wanted(SettingsSchema s) {
        return s.findRow(MinerSettingsSchema.WANTED_ORES).orElseThrow();
    }

    @Test
    void towntopiasOwnRowsGiveTowntopiasSchema() {
        List<WorkListsPayload.Row> rows = MinerScreenRows.rows(TOWNTOPIA);
        assertEquals(7, rows.size());
        assertEquals("coal_ore", rows.get(0).id());
        assertEquals(0, rows.get(0).stock());
        assertEquals(TOWNTOPIA, MinerScreenRows.schema(TOWNTOPIA, rows), "same rows, same page");
    }

    @Test
    void addedOresAreOffByDefaultAndTowntopiasKeepTheirs() {
        List<WorkListsPayload.Row> rows = new ArrayList<>(MinerScreenRows.rows(TOWNTOPIA));
        rows.add(new WorkListsPayload.Row("mymod:tin", "Tin", "Best mine depth: Y 0 to 64.", 0));
        SettingsSchema s = MinerScreenRows.schema(TOWNTOPIA, rows);
        List<SettingsSchema.AllowListEntry> entries = wanted(s).allowListEntries();
        assertEquals(8, entries.size());
        assertEquals("mymod:tin", entries.get(7).id());
        assertEquals("Tin", entries.get(7).label());
        assertFalse(entries.get(7).defaultAllowed());
        assertSame(wanted(TOWNTOPIA).allowListEntries().get(0), entries.get(0), "Towntopia's own entries stay the very objects");
        assertTrue(new WorkListsPayload(1, java.util.Optional.empty(), java.util.Optional.of(rows)).valid());
    }

    @Test
    void everyOtherRowIsTowntopias() {
        List<WorkListsPayload.Row> rows = new ArrayList<>(MinerScreenRows.rows(TOWNTOPIA));
        rows.add(new WorkListsPayload.Row("mymod:tin", "Tin", "", 0));
        SettingsSchema s = MinerScreenRows.schema(TOWNTOPIA, rows);
        assertEquals(TOWNTOPIA.sections().size(), s.sections().size());
        for (int i = 0; i < s.sections().size(); i++) {
            assertEquals(TOWNTOPIA.sections().get(i).title(), s.sections().get(i).title());
            for (int r = 0; r < s.sections().get(i).rows().size(); r++) {
                SettingsSchema.Row a = s.sections().get(i).rows().get(r);
                SettingsSchema.Row b = TOWNTOPIA.sections().get(i).rows().get(r);
                if (!a.settingId().equals(MinerSettingsSchema.WANTED_ORES)) assertSame(b, a);
            }
        }
    }

    @Test
    void aRemovedOreRowIsLeftOut() {
        List<WorkListsPayload.Row> rows = new ArrayList<>(MinerScreenRows.rows(TOWNTOPIA));
        rows.remove(0);
        assertEquals(6, wanted(MinerScreenRows.schema(TOWNTOPIA, rows)).allowListEntries().size());
    }
}
