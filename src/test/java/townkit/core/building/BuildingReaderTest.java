package townkit.core.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** The role lists the read API adds to {@code functionalBlocks} exist in Towntopia's jar with the declared types. */
class BuildingReaderTest {
    @Test
    void roleFieldsMatchTowntopia() throws Exception {
        for (Map.Entry<String, List<Map.Entry<String, Class<?>>>> e : BuildingReader.ROLE_FIELDS.entrySet()) {
            Class<?> c = Class.forName("com.alex.test.village.building." + e.getKey(), false,
                    BuildingReaderTest.class.getClassLoader());
            for (Map.Entry<String, Class<?>> f : e.getValue()) {
                Field field = c.getDeclaredField(f.getKey());
                assertEquals(f.getValue(), field.getType(), e.getKey() + "." + f.getKey());
                assertTrue(field.getGenericType() instanceof ParameterizedType p
                        && p.getActualTypeArguments()[0] == BlockPos.class, e.getKey() + "." + f.getKey() + " holds BlockPos");
            }
        }
    }

    @Test
    void kitchenCraftingTablesAreARoleList() {
        assertTrue(BuildingReader.ROLE_FIELDS.get("KitchenBuilding").stream()
                .anyMatch(f -> f.getKey().equals("craftingTableBlocks")));
    }
}
