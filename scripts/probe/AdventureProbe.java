package dev.flint.planetrycore.probe;

import dev.flint.planetry.core.PlanetryCore;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/** Real Paper world/items and packaged Core, controlled players and optional RPG API shapes. */
public final class AdventureProbe extends JavaPlugin {
    private PlanetryCore core;
    private Fake owner, other;
    private Block ore, crop;
    private boolean hooks, finished;
    private String stage = "startup";
    private int initialNodes;

    @Override public void onEnable() {
        after(1, () -> {
            core = (PlanetryCore) Bukkit.getPluginManager().getPlugin("PlanetryCore");
            check(core != null && core.isEnabled(), "Core did not enable");
            hooks = Bukkit.getPluginManager().isPluginEnabled("MMOItems");
            owner = new Fake("AdventureOwner", true); other = new Fake("AdventureOther", false);
            World world = Bukkit.getWorlds().getFirst();
            world.addPluginChunkTicket(0, 0, this);
            Block cocoaSupportProbe = world.getBlockAt(4,70,0);
            cocoaSupportProbe.getRelative(BlockFace.EAST).setType(Material.JUNGLE_LOG,false);
            check(Bukkit.createBlockData("minecraft:cocoa[age=2,facing=east]").isSupported(cocoaSupportProbe),"Native cocoa support direction differs from saved node support");
            ore = world.getBlockAt(0, 70, 0); ore.setType(Material.OBSIDIAN, false);
            crop = world.getBlockAt(2, 70, 0); crop.getRelative(BlockFace.DOWN).setType(Material.FARMLAND, false);
            crop.setType(Material.WHEAT, false);
            Ageable mature = (Ageable) crop.getBlockData(); mature.setAge(mature.getMaximumAge()); crop.setBlockData(mature, false);
            owner.target = ore; other.target = ore;
            fire(new PlayerJoinEvent(owner.player, Component.empty())); fire(new PlayerJoinEvent(other.player, Component.empty()));
            waitFor(() -> "false".equals(found("first_ore"))
                    && "false".equals(core.journal().placeholder(other.id,"journal_first_ore_found")), this::permissionsAndMenus);
        });
    }

    private void permissionsAndMenus() {
        stage = "permissions, public menus and command entries";
        check(core.getServer().getPluginManager().getPermission("planetry.admin.gamexp") != null, "Corrected game XP permission missing");
        check(core.getServer().getPluginManager().getPermission("planetry.adam.gamexp") == null, "Retired typo permission remains");
        check("1".equals(core.journal().placeholder(owner.id, "journal_mining_max")), "Category max placeholder wrong");
        execute("journal", other.player);
        check(other.top != null && other.top.getSize() >= 9, "Public Journal did not open");
        int count = other.inventoryCount();
        InventoryClickEvent click = new InventoryClickEvent(other.view, InventoryType.SlotType.CONTAINER, 0, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        fire(click); check(click.isCancelled() && other.inventoryCount() == count, "Journal items can be extracted");
        InventoryDragEvent drag = new InventoryDragEvent(other.view, null, ItemStack.of(Material.STONE), false, Map.of(0,ItemStack.of(Material.STONE)));
        fire(drag); check(drag.isCancelled(), "Journal drag accepted");
        execute("journaladmin", other.player, owner.id.toString(), "add", "command_entry");
        after(3, () -> {
            check("false".equals(found("command_entry")), "Unprivileged Journal write succeeded");
            execute("journaladmin", Bukkit.getConsoleSender(), owner.id.toString(), "add", "command_entry");
            waitFor(() -> "true".equals(found("command_entry")), () -> {
                execute("journal", owner.player, "achievements");
                check(owner.top != null && owner.top.getSize() == 54, "Self category console-open link failed");
                execute("journaladmin", Bukkit.getConsoleSender(), owner.id.toString(), "remove", "command_entry");
                waitFor(() -> "false".equals(found("command_entry")), this::bind);
            });
        });
    }

    private void bind() {
        stage = "staff binding and per-location protection";
        initialNodes = core.harvest().nodeCount();
        execute("harvest", other.player, "wand", "ore");
        check(other.inventoryCount() == 0, "Unprivileged harvest wand was granted");
        execute("harvest", owner.player, "wand", "ore");
        ItemStack wand = Arrays.stream(owner.items).filter(Objects::nonNull).filter(i -> i.getType() == Material.BLAZE_ROD).findFirst().orElseThrow();
        owner.items[0] = wand;
        fire(new PlayerInteractEvent(owner.player, Action.RIGHT_CLICK_BLOCK, wand, ore, BlockFace.UP, EquipmentSlot.HAND));
        check(core.harvest().nodeCount() == initialNodes + 1, "Wand did not bind exact block");
        Block ordinary = ore.getRelative(BlockFace.EAST); ordinary.setType(Material.OBSIDIAN, false);
        BlockBreakEvent normal = new BlockBreakEvent(ordinary, owner.player); fire(normal);
        check(!normal.isCancelled(), "Ordinary same-material block treated as node");
        BlockBreakEvent protectedBreak = new BlockBreakEvent(ore, owner.player); protectedBreak.setCancelled(true); fire(protectedBreak);
        check(ore.getType() == Material.OBSIDIAN && owner.customCount() == 0, "Cancelled protection break harvested");
        if (!hooks) {
            BlockBreakEvent missing = new BlockBreakEvent(ore, owner.player); fire(missing);
            after(3, () -> {
                check(ore.getType() == Material.OBSIDIAN && owner.customCount() == 0 && "false".equals(found("first_ore")), "Missing RPG plugins consumed node or granted false item");
                succeed();
            });
            return;
        }
        readiness(false);
        BlockBreakEvent blocked = new BlockBreakEvent(ore, owner.player); fire(blocked);
        after(3, () -> {
            check(ore.getType() == Material.OBSIDIAN && owner.customCount() == 0, "Loading inventory received rewards");
            readiness(true); Hooks.loaded(false);
            fire(new BlockBreakEvent(ore, owner.player));
            after(3, () -> {
                check(ore.getType() == Material.OBSIDIAN, "Unloaded AuraSkills user consumed node");
                Hooks.loaded(true); harvestOre();
            });
        });
    }

    private void harvestOre() {
        stage = "ore drops, XP and exactly one Journal notification";
        check(new dev.flint.planetry.core.integration.MmoItemsHook(core).apply("MATERIAL","ORE") != null,"MMOItems API failed");
        check(dev.flint.planetry.core.integration.AuraSkillsHook.available(owner.player,"mining"),"AuraSkills API failed");
        owner.messages.clear(); owner.components.clear();
        BlockBreakEvent harvested = new BlockBreakEvent(ore, owner.player); fire(harvested);
        check(harvested.isCancelled(), "Managed break did not suppress vanilla harvest");
        waitFor(() -> ore.getType() == Material.BEDROCK && owner.customCount() == 2 && "true".equals(found("first_ore")), () -> {
            check(Hooks.xp(owner.id) == 3.5, "AuraSkills amount wrong or duplicated");
            check(owner.components.stream().anyMatch(AdventureProbe::hasInteractiveItem), "Unlock category link lost hover/click");
            check(owner.components.stream().anyMatch(AdventureProbe::hasLoreHover), "Unlock item lost its actual lore hover metadata");
            int messages = owner.messages.size();
            core.journal().harvest(owner.player, "ore");
            fire(new BlockBreakEvent(ore, owner.player));
            after(3, () -> {
                check(owner.customCount() == 2 && Hooks.xp(owner.id) == 3.5 && owner.messages.size() == messages,
                        "Cooldown or duplicate trigger repeated a reward");
                stage = "ore regeneration in loaded chunk";
                waitFor(() -> ore.getType() == Material.OBSIDIAN, this::overflow);
            });
        });
    }

    private void overflow() {
        stage = "glowing private overflow and pickup ownership";
        owner.full = true;
        Set<UUID> before = new HashSet<>(); ore.getWorld().getEntitiesByClass(Item.class).forEach(item -> before.add(item.getUniqueId()));
        fire(new BlockBreakEvent(ore, owner.player));
        waitFor(() -> ore.getWorld().getEntitiesByClass(Item.class).stream().anyMatch(item -> !before.contains(item.getUniqueId())), () -> {
            Item drop = ore.getWorld().getEntitiesByClass(Item.class).stream().filter(item -> !before.contains(item.getUniqueId())).findFirst().orElseThrow();
            String ownerId = drop.getPersistentDataContainer().get(new NamespacedKey(core,"harvest-owner"), PersistentDataType.STRING);
            check(owner.id.toString().equals(ownerId) && drop.isGlowing() && !drop.isVisibleByDefault(), "Overflow not owner-tagged/glowing/private by default");
            EntityPickupItemEvent steal = new EntityPickupItemEvent(other.player, drop, 0); fire(steal); check(steal.isCancelled(), "Other player can pick up private drop");
            InventoryPickupItemEvent hopper = new InventoryPickupItemEvent(Bukkit.createInventory(null,9),drop); fire(hopper); check(hopper.isCancelled(), "Hopper collected private drop");
            readiness(false);
            EntityPickupItemEvent loading = new EntityPickupItemEvent(owner.player,drop,0); fire(loading); check(loading.isCancelled(), "Owner loading inventory can collect drop");
            readiness(true);
            EntityPickupItemEvent own = new EntityPickupItemEvent(owner.player,drop,0); fire(own); check(!own.isCancelled(), "Ready owner cannot collect own drop");
            drop.remove(); owner.full = false;
            crops();
        });
    }

    private void crops() {
        stage = "crop stage regeneration and MythicMob trigger";
        execute("harvest",owner.player,"wand","crop");
        ItemStack wand = Arrays.stream(owner.items).filter(Objects::nonNull).filter(i -> i.getType() == Material.BLAZE_ROD)
                .reduce((a,b)->b).orElseThrow();
        owner.items[0]=wand; owner.target=crop;
        fire(new PlayerInteractEvent(owner.player,Action.RIGHT_CLICK_BLOCK,wand,crop,BlockFace.UP,EquipmentSlot.HAND));
        check(core.harvest().nodeCount()==initialNodes+2,"Crop not bound");
        fire(new BlockBreakEvent(crop,owner.player));
        waitFor(()-> crop.getBlockData() instanceof Ageable age && age.getAge()==0,()-> {
            fire(new BlockBreakEvent(crop,owner.player));
            waitFor(()->crop.getBlockData() instanceof Ageable age && age.getAge()>0 && age.getAge()<age.getMaximumAge(),()-> {
                waitFor(()->crop.getBlockData() instanceof Ageable age && age.getAge()==age.getMaximumAge(),()-> {
                    waitFor(()->"true".equals(found("first_crop")),()-> {
                        Hooks.kill(owner.player);
                        waitFor(()->"true".equals(found("first_mob")),this::reconnect);
                    });
                });
            });
        });
    }

    private void reconnect() {
        stage = "Journal reload after disconnect";
        check(Hooks.placeholders(owner.player).equals("true|1|1"), "Actual PAPI Journal expansion mismatch");
        fire(new PlayerQuitEvent(owner.player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        check(found("first_ore").isEmpty(), "Quit retained Journal cache");
        fire(new PlayerJoinEvent(owner.player, Component.empty()));
        waitFor(() -> "true".equals(found("first_ore")) && "true".equals(found("first_mob")), this::succeed);
    }

    private void succeed() {
        check(core.isEnabled(),"Core disabled during acceptance");
        finished=true;
        getLogger().info("CORE_ADVENTURE_PROBE_OK: packaged Core, real Paper blocks/items, controlled players; "+(hooks?"controlled MMOItems/AuraSkills/MythicMobs APIs; node binding, cooldown, stage growth, private drops, readiness, Journal menus/permissions/placeholders/triggers":"missing optional APIs refuse harvesting; public Journal/admin permissions"));
        Bukkit.shutdown();
    }
    private String found(String id) { return core.journal().placeholder(owner.id,"journal_"+id+"_found"); }
    private void readiness(boolean state) {
        Plugin sync=Bukkit.getPluginManager().getPlugin("NetworkInventorySync");
        if(sync!=null)try{sync.getClass().getField("ready").setBoolean(sync,state);}catch(Exception e){throw new IllegalStateException(e);}
    }
    private static boolean hasLoreHover(Component value) {
        var hover = value.hoverEvent();
        return hover != null && hover.action() == net.kyori.adventure.text.event.HoverEvent.Action.SHOW_ITEM
                && hover.value() instanceof net.kyori.adventure.text.event.HoverEvent.ShowItem item
                && item.dataComponents().containsKey(net.kyori.adventure.key.Key.key("minecraft:lore"))
                || value.children().stream().anyMatch(AdventureProbe::hasLoreHover);
    }
    private static boolean hasInteractiveItem(Component value) {
        return value.hoverEvent()!=null && value.clickEvent()!=null && value.clickEvent().action()==ClickEvent.Action.RUN_COMMAND
                || value.children().stream().anyMatch(AdventureProbe::hasInteractiveItem);
    }
    private void execute(String name,CommandSender sender,String...args) { core.getCommand(name).execute(sender,name,args); }
    private void fire(Event event) { Bukkit.getPluginManager().callEvent(event); }
    private void after(int ticks,Runnable action) { Bukkit.getScheduler().runTaskLater(this,()->{try{if(!finished)action.run();}catch(Throwable e){fail(e);}},ticks); }
    private void waitFor(BooleanSupplier condition,Runnable next) {
        new BukkitRunnable(){int ticks;public void run(){try{if(finished){cancel();return;}if(condition.getAsBoolean()){cancel();next.run();}else if(++ticks>400){cancel();fail(new IllegalStateException("Timed out; ore="+ore.getType()+"; items="+owner.customCount()+"; found="+found("first_ore")+"; messages="+owner.messages));}}catch(Throwable e){cancel();fail(e);}}}.runTaskTimer(this,1,1);
    }
    private void fail(Throwable error) { if(finished)return;finished=true;getLogger().severe("CORE_ADVENTURE_PROBE_FAILED: "+stage+": "+error);error.printStackTrace();Bukkit.shutdown(); }
    private static void check(boolean result,String text){if(!result)throw new IllegalStateException(text);}
    @SuppressWarnings("unchecked") private static <T>T proxy(Class<T> type,java.lang.reflect.InvocationHandler handler){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
    private static Object zero(Class<?> type){if(!type.isPrimitive())return null;if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;if(type==double.class)return 0d;if(type==float.class)return 0f;if(type==short.class)return (short)0;if(type==byte.class)return (byte)0;if(type==char.class)return (char)0;return null;}

    private final class Fake {
        final UUID id=UUID.randomUUID(); final String name; final boolean admin;
        final ItemStack[] items=new ItemStack[41]; final List<String>messages=new ArrayList<>();final List<Component>components=new ArrayList<>();
        Player player;final PlayerInventory inventory;Inventory top;InventoryView view;Block target;boolean full;
        Fake(String name,boolean admin){
            this.name=name;this.admin=admin;
            inventory=proxy(PlayerInventory.class,(object,method,args)->{
                check(Bukkit.isPrimaryThread(),"Inventory off main thread");
                return switch(method.getName()){
                    case "getSize"->41;case "getMaxStackSize"->64;case "getHolder"->player;case "getType"->InventoryType.PLAYER;
                    case "getContents"->items.clone();case "getStorageContents"->Arrays.copyOf(items,36);
                    case "getItem"->items[(int)args[0]];case "setItem"->{items[(int)args[0]]=(ItemStack)args[1];yield null;}
                    case "getItemInMainHand"->items[0]==null?ItemStack.of(Material.AIR):items[0];
                    case "getItemInOffHand"->ItemStack.of(Material.AIR);
                    case "getHeldItemSlot"->0;case "firstEmpty"->firstEmpty();
                    case "addItem"->{Map<Integer,ItemStack>left=new HashMap<>();ItemStack[] incoming=(ItemStack[])args[0];for(int n=0;n<incoming.length;n++){int slot=firstEmpty();if(full||slot<0)left.put(n,incoming[n].clone());else items[slot]=incoming[n].clone();}yield left;}
                    case "setStorageContents"->{System.arraycopy(args[0],0,items,0,36);yield null;}
                    case "hashCode"->System.identityHashCode(object);case "equals"->object==args[0];default->zero(method.getReturnType());
                };
            });
            player=proxy(Player.class,(object,method,args)->switch(method.getName()){
                case "getUniqueId"->id;case "getName","getDisplayName"->name;case "displayName","name"->Component.text(name);
                case "getServer"->Bukkit.getServer();case "getPlayer"->object;
                case "isOnline","isValid","hasPlayedBefore","isConnected"->true;case "isDead","isSneaking"->false;
                case "hasPermission","isPermissionSet","isOp"->admin;
                case "getGameMode"->GameMode.SURVIVAL;case "getInventory"->inventory;case "getOpenInventory"->view;
                case "getWorld"->Bukkit.getWorlds().getFirst();case "getLocation"->ore==null?Bukkit.getWorlds().getFirst().getSpawnLocation():ore.getLocation();
                case "getTargetBlockExact"->target;
                case "sendMessage"->{for(Object value:args){if(value instanceof Component c){components.add(c);messages.add(PlainTextComponentSerializer.plainText().serialize(c));}else if(value instanceof String s)messages.add(s);}yield null;}
                case "openInventory"->{top=(Inventory)args[0];view=makeView();yield view;}
                case "closeInventory","updateInventory","showEntity","hideEntity","sendExperienceChange"->null;
                case "getLevel","getTotalExperience"->0;case "getExp"->0f;
                case "hashCode"->System.identityHashCode(object);case "equals"->object==args[0];case "toString"->name;
                default->zero(method.getReturnType());
            });
        }
        int firstEmpty(){for(int i=0;i<36;i++)if(items[i]==null||items[i].getType().isAir())return i;return -1;}
        int inventoryCount(){return Arrays.stream(items).filter(Objects::nonNull).mapToInt(ItemStack::getAmount).sum();}
        int customCount(){return Arrays.stream(items).filter(Objects::nonNull).filter(i->i.getType()==Material.DIAMOND).mapToInt(ItemStack::getAmount).sum();}
        InventoryView makeView(){return proxy(InventoryView.class,(object,method,args)->switch(method.getName()){
            case "getPlayer"->player;case "getTopInventory"->top;case "getBottomInventory"->inventory;case "getType"->InventoryType.CHEST;
            case "countSlots"->top.getSize()+36;case "convertSlot"->(int)args[0];case "getSlotType"->InventoryType.SlotType.CONTAINER;
            case "getInventory"->(int)args[0]<top.getSize()?top:inventory;
            case "getItem"->(int)args[0]<top.getSize()?top.getItem((int)args[0]):null;case "getCursor"->ItemStack.of(Material.AIR);
            case "hashCode"->System.identityHashCode(object);case "equals"->object==args[0];default->zero(method.getReturnType());
        });}
    }
    private static final class Hooks {
        static String placeholders(Player player){return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player,"%planetry_journal_first_ore_found%|%planetry_journal_mining_unlocked%|%planetry_journal_mining_max%");}
        static void loaded(boolean state){AuraFixture.loaded=state;}
        static double xp(UUID id){return AuraFixture.xp.getOrDefault(id,0d);}
        static void kill(Player player){Bukkit.getPluginManager().callEvent(new io.lumine.mythic.bukkit.events.MythicMobDeathEvent(player,"ProbeMob"));}
    }
}
