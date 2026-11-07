package com.explorermap.structure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StructureIdsTest {

    @Test
    void biomeVariantsFoldIntoOneType() {
        for (String village : new String[] {"village", "village_plains", "village_desert", "village_savanna",
                "village_snowy", "village_taiga"}) {
            assertEquals("village", StructureIds.normalize(village), village);
        }
        assertEquals("mineshaft", StructureIds.normalize("mineshaft_mesa"));
        assertEquals("woodland_mansion", StructureIds.normalize("mansion"));
    }

    @Test
    void otherIdsAreLeftAlone() {
        assertEquals("desert_pyramid", StructureIds.normalize("desert_pyramid"));
        assertEquals("villager_hut", StructureIds.normalize("villager_hut"));
    }

    @Test
    void vanillaIdsGetTheirIconAndName() {
        assertEquals("explorermap:village", StructureIds.icon("village_taiga"));
        assertEquals("explorermap:temple", StructureIds.icon("jungle_pyramid"));
        assertEquals("explorermap:dungeon", StructureIds.icon("mineshaft_mesa"));
        assertEquals("explorermap:dungeon", StructureIds.icon("mansion"));
        assertEquals("explorermap:pin", StructureIds.icon("igloo"));
        assertEquals("explorermap.structure.village", StructureIds.nameKey("village_savanna"));
    }

    @Test
    void storedNamesMapBackToTheirType() {
        assertEquals("village", StructureIds.typeOfStoredName("explorermap.structure.village"));
        assertEquals("village", StructureIds.typeOfStoredName("explorermap.structure.village (3)"));
        // Written by older versions.
        assertEquals("village", StructureIds.typeOfStoredName("Village Plains"));
        assertEquals("desert_pyramid", StructureIds.typeOfStoredName("Desert Pyramid (2)"));
    }
}
