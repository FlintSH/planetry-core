package dev.flint.planetrycore.probe;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import dev.aurelium.auraskills.api.AuraSkillsApi;
import dev.aurelium.auraskills.api.registry.GlobalRegistry;
import dev.aurelium.auraskills.api.skill.Skill;
import dev.aurelium.auraskills.api.user.SkillsUser;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
/** Only a controlled API fixture. XP is recorded to verify hook arguments and readiness. */
public final class AuraFixture extends JavaPlugin {
    public static boolean loaded = true;
    public static final Map<UUID, Double> xp = new HashMap<>();
    public static final Skill SKILL = new Skill() { };
    public static final AuraSkillsApi api = new AuraSkillsApi() {
        public GlobalRegistry getGlobalRegistry() { return id -> id.namespace().equals("auraskills") && Set.of("mining", "farming").contains(id.key()) ? SKILL : null; }
        public SkillsUser getUser(UUID id) { return new SkillsUser() {
            public boolean isLoaded() { return loaded; }
            public void addSkillXp(Skill skill,double amount) {
                if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("AuraSkills off main thread");
                if (!loaded || skill != SKILL) throw new IllegalStateException("Invalid AuraSkills grant");
                xp.merge(id,amount,Double::sum);
            }
        }; }
    };
}
