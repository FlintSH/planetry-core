package dev.aurelium.auraskills.api;
import java.util.UUID;
import dev.aurelium.auraskills.api.registry.GlobalRegistry;
import dev.aurelium.auraskills.api.user.SkillsUser;
import dev.flint.planetrycore.probe.AuraFixture;
public interface AuraSkillsApi {
    static AuraSkillsApi get() { return AuraFixture.api; }
    GlobalRegistry getGlobalRegistry();
    SkillsUser getUser(UUID id);
}
