package io.lumine.mythic.bukkit.events;
import io.lumine.mythic.api.mobs.MythicMob;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
public final class MythicMobDeathEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final LivingEntity killer;
    private final MythicMob type;
    public MythicMobDeathEvent(LivingEntity killer, String id) { this.killer = killer; type = () -> id; }
    public LivingEntity getKiller() { return killer; }
    public MythicMob getMobType() { return type; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
