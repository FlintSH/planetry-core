package dev.flint.planetry.core.harvest;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class HarvestSettingsTest {
    private YamlConfiguration config() {
        var config = new YamlConfiguration(); config.set("enabled", true);
        config.set("profiles.ore.source", "OBSIDIAN"); config.set("profiles.ore.cooldown-block", "BEDROCK");
        config.set("profiles.ore.cooldown-seconds", 60);
        config.set("profiles.ore.drops", List.of(Map.of("type", "MATERIAL", "id", "ORE", "amount", 2)));
        config.set("profiles.ore.skill", "auraskills/mining"); config.set("profiles.ore.skill-xp", 2.5);
        return config;
    }
    @Test void shippedExamplesAreDisabledAndHaveEditableMMOItemsProfiles() {
        var yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/harvest.yml"), StandardCharsets.UTF_8));
        var settings = HarvestSettings.read(yaml);
        assertFalse(settings.enabled()); assertEquals(3, settings.profiles().size());
        assertTrue(settings.profiles().get("wheat").growth());
        assertEquals(Material.AIR, settings.profiles().get("oak").cooldown());
    }
    @Test void profilesKeepExactItemIdsAndFractionalSkillXp() {
        var settings = HarvestSettings.read(config()); var ore = settings.profiles().get("ore");
        assertEquals("ORE", ore.drops().getFirst().id()); assertEquals(2, ore.drops().getFirst().amount()); assertEquals(2.5, ore.skillXp());
        assertThrows(UnsupportedOperationException.class, () -> settings.profiles().clear());
    }
    @Test void malformedValuesCannotEnableUnboundedOrAmbiguousRewards() {
        for (var entry : Map.<String, Object>ofEntries(Map.entry("enabled", "true"), Map.entry("max-nodes", 10001),
                Map.entry("poll-ticks", 0), Map.entry("profiles.ore.source", "NOT_A_BLOCK"),
                Map.entry("profiles.ore.cooldown-seconds", 0), Map.entry("profiles.ore.growth-stages", "true"),
                Map.entry("profiles.ore.skill-xp", Double.NaN), Map.entry("profiles.ore.skill", ""),
                Map.entry("profiles.ore.drops", List.of(Map.of("type", "MATERIAL", "id", "ORE", "amount", -1)))).entrySet()) {
            var yaml = config(); yaml.set(entry.getKey(), entry.getValue());
            assertThrows(IllegalArgumentException.class, () -> HarvestSettings.read(yaml), entry.getKey());
        }
    }
    @Test void safeCustomCooldownMaterialsAndNoSkillXpAreSupported() {
        var yaml = config(); yaml.set("profiles.ore.cooldown-block", "POLISHED_ANDESITE"); yaml.set("profiles.ore.skill", ""); yaml.set("profiles.ore.skill-xp", 0);
        assertEquals(Material.POLISHED_ANDESITE, HarvestSettings.read(yaml).profiles().get("ore").cooldown());
    }
}
