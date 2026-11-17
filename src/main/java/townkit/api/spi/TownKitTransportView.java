package townkit.api.spi;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.ApiStatus;
import townkit.api.Subscription;
import townkit.api.nav.TransportKind;
import townkit.api.nav.TransportLink;
import townkit.api.village.Village;

/** What {@link townkit.api.nav.TownKitTransport} asks TownKit's backend for. Addons use TownKitTransport instead. */
@ApiStatus.Internal
public interface TownKitTransportView {
    Subscription registerKind(String owner, TransportKind kind);

    Subscription enable(String owner, ResourceLocation kindId, Item marker);

    List<TransportLink> links(Village village);
}
