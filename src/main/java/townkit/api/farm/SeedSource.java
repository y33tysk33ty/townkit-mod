package townkit.api.farm;

import net.minecraft.world.item.Item;

/**
 * What the farmer makes a seed from: {@code seedsPerProduce} of the seed for each {@code produce} she turns in
 * ({@link TownKitFarming#seedSources()}).
 *
 * @param produce the harvest item the seeds are made from
 * @param seedsPerProduce how many seeds one produce item makes, at least 1
 * @since 0.8.0
 */
public record SeedSource(Item produce, int seedsPerProduce) {
}
