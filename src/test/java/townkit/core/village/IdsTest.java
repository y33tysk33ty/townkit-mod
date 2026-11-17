package townkit.core.village;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.alex.test.village.building.KitchenBuilding;
import com.alex.test.village.building.MerchantStallBuilding;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class IdsTest {
    @Test
    void professionIds() {
        assertEquals(ResourceLocation.parse("towntopia:farmer"), Ids.profession("FARMER"));
        assertEquals(ResourceLocation.parse("towntopia:some_future_job"), Ids.profession("SOME_FUTURE_JOB"));
    }

    @Test
    void buildingIds() {
        assertEquals("towntopia:kitchen", Ids.building(KitchenBuilding.class).toString());
        assertEquals("towntopia:merchant_stall", Ids.building(MerchantStallBuilding.class).toString());
    }
}
