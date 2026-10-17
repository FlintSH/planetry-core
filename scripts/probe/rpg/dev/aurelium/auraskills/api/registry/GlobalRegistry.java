package dev.aurelium.auraskills.api.registry;
import dev.aurelium.auraskills.api.skill.Skill;
public interface GlobalRegistry { Skill getSkill(NamespacedId id); }
