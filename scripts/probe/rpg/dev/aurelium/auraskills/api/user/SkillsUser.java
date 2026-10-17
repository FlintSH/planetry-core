package dev.aurelium.auraskills.api.user;
import dev.aurelium.auraskills.api.skill.Skill;
public interface SkillsUser { boolean isLoaded(); void addSkillXp(Skill skill,double amount); }
