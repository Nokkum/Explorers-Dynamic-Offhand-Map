package com.explorermap.data;

import com.explorermap.ExplorerMapMod;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;

import java.util.ArrayList;
import java.util.List;

final class CodecUtil {

    private CodecUtil() {}

    static <A> Codec<List<A>> boundedList(Codec<A> elementCodec, int maxSize, String label) {
        return new Codec<List<A>>() {
            @Override
            public <T> DataResult<T> encode(List<A> input, DynamicOps<T> ops, T prefix) {
                return elementCodec.listOf().encode(input, ops, prefix);
            }

            @Override
            public <T> DataResult<Pair<List<A>, T>> decode(DynamicOps<T> ops, T input) {
                return ops.getList(input).flatMap(elements -> {
                    List<A> result = new ArrayList<>();
                    int[] totalSeen = {0};

                    elements.accept(rawElement -> {
                        totalSeen[0]++;
                        if (result.size() >= maxSize) return; // cap already reached - skip decoding entirely
                        elementCodec.parse(ops, rawElement)
                                .resultOrPartial(err -> ExplorerMapMod.LOGGER.debug(
                                        "[ExplorerMap] Skipping an invalid '{}' entry while loading: {}",
                                        label, err))
                                .ifPresent(result::add);
                    });

                    if (totalSeen[0] > maxSize) {
                        ExplorerMapMod.LOGGER.warn(
                                "[ExplorerMap] Saved data has {} '{}' entries, more than the maximum of {} - "
                                        + "the excess entries were not loaded. The save may be corrupted or "
                                        + "was edited by hand.",
                                totalSeen[0], label, maxSize);
                    }

                    return DataResult.success(Pair.of(result, input));
                });
            }
        };
    }
}
