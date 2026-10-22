package dev.flint.planetrycore.probe;

import dev.flint.planetry.core.PlanetryCore;
import dev.flint.planetry.core.progression.Progress;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.damage.DamageSource;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.view.AnvilView;

/** Controlled player doubles in real Paper; this is not a network-client acceptance test. */
public final class CoreProbe extends JavaPlugin implements CommandExecutor {
    private PlanetryCore core;
    private Plugin vault;
    private Plugin inventory;
    private Fake player;
    private CommandSender console;
    private final List<String> consoleMessages = new ArrayList<>();
    private final List<String[]> rewards = new ArrayList<>();
    private boolean finished;
    private String declinedClaim;

    @Override public void onEnable() {
        getCommand("coreprobeaward").setExecutor(this);
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                Plugin loaded = Bukkit.getPluginManager().getPlugin("PlanetryCore");
                check(loaded instanceof PlanetryCore && loaded.isEnabled(), "PlanetryCore did not enable");
                core = (PlanetryCore) loaded;
                vault = Bukkit.getPluginManager().getPlugin("Vault");
                inventory = Bukkit.getPluginManager().getPlugin("NetworkInventorySync");
                check(vault != null && vault.isEnabled(), "Test economy did not enable");
                check(Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI"), "Actual PlaceholderAPI did not enable");
                check(PlaceholderAPI.isRegistered("planetry"), "Planetry placeholder expansion was not registered");
                PluginCommand alias = Bukkit.getPluginCommand("levels");
                check(alias != null && alias.getPlugin() == core, "/levels alias is not registered");
                console = sender("CoreProbeConsole", true, consoleMessages);
                player = new Fake();
                var join = new PlayerJoinEvent(player.player,
                        Component.translatable("multiplayer.player.joined", Component.text("CoreProbe")));
                Bukkit.getPluginManager().callEvent(join);
                check(join.joinMessage() == null, "Core allowed the vanilla backend join announcement");
                waitFor(() -> progress() != null, this::initial);
            } catch (Throwable error) { fail(error); }
        });
    }

    private void initial() {
        checkProgress(0, 0);
        check("0|0".equals(placeholders()), "Initial placeholders differ");
        vanillaLock();
        advancementLock();
    }

    private void commands() {
        player.messages.clear();
        execute("level", player.player);
        check(!player.messages.isEmpty(), "Unprivileged /level produced no response");
        player.messages.clear();
        execute("levels", player.player);
        check(!player.messages.isEmpty(), "Unprivileged /levels produced no response");
        player.messages.clear();
        execute("gamexp", player.player, "give", player.id.toString(), "250");
        check(!player.messages.isEmpty(), "Denied staff command produced no response");
        after(4, () -> {
            checkProgress(0, 0);
            check(money() == 0 && deposits() == 0 && rewards.isEmpty(), "Permission denial awarded progress or rewards");
            readiness(false);
            execute("gamexp", console, "give", player.id.toString(), "250");
            waitFor(() -> state(2, 50), () -> after(8, () -> {
                check(deposits() == 0 && rewards.isEmpty(), "Rewards ran before Inventory Sync was ready");
                readiness(true);
                waitFor(() -> deposits() == 2 && rewards.size() == 2, this::awarded);
            }));
        });
    }

    private void advancementLock() {
        var advancement = Bukkit.getAdvancement(NamespacedKey.minecraft("story/root"));
        check(advancement != null, "The vanilla advancement used by the probe is missing");
        player.allowXpRestore = true;
        player.expectedXpWrites = 3;
        Bukkit.getPluginManager().callEvent(new PlayerAdvancementDoneEvent(player.player, advancement, Component.empty()));
        player.vanillaLevel = 18;
        player.vanillaProgress = .6f;
        player.vanillaTotal = 400;
        // A second completion in the same tick must preserve the FIRST baseline.
        Bukkit.getPluginManager().callEvent(new PlayerAdvancementDoneEvent(player.player, advancement, Component.empty()));
        player.vanillaLevel = 19;
        player.vanillaProgress = .75f;
        player.vanillaTotal = 500;
        after(3, () -> {
            checkVanilla();
            player.allowXpRestore = false;
            checkProgress(0, 0);
            check("0|0".equals(placeholders()), "Advancement XP changed game progression");
            readiness(false);
            Bukkit.getPluginManager().callEvent(new PlayerAdvancementDoneEvent(player.player, advancement, Component.empty()));
            driftVanilla(player);
            after(3, () -> {
                check(player.vanillaLevel == 19 && player.vanillaTotal == 500 && player.xpSetterCalls == 3,
                        "Advancement guard captured or restored XP while Inventory Sync was loading");
                resetVanilla(player);
                readiness(true);
                Bukkit.getPluginManager().callEvent(new PlayerAdvancementDoneEvent(player.player, advancement, Component.empty()));
                driftVanilla(player);
                readiness(false);
                after(3, () -> {
                    check(player.vanillaLevel == 19 && player.vanillaTotal == 500 && player.xpSetterCalls == 3,
                            "Advancement guard restored XP across an Inventory Sync readiness transition");
                    resetVanilla(player);
                    readiness(true);
                    Fake previous = player;
                    Bukkit.getPluginManager().callEvent(new PlayerAdvancementDoneEvent(previous.player, advancement, Component.empty()));
                    driftVanilla(previous);
                    previous.online = false;
                    Component quitMessage = Component.translatable("multiplayer.player.left", Component.text("CoreProbe"));
                    var quit = new PlayerQuitEvent(previous.player, quitMessage, PlayerQuitEvent.QuitReason.DISCONNECTED);
                    Bukkit.getPluginManager().callEvent(quit);
                    check(quitMessage.equals(quit.quitMessage()), "Join suppression changed the quit announcement");
                    player = new Fake(previous.id);
                    var join = new PlayerJoinEvent(player.player, (Component) null);
                    Bukkit.getPluginManager().callEvent(join);
                    check(join.joinMessage() == null, "Core replaced an already suppressed join announcement");
                    after(3, () -> {
                        check(previous.vanillaLevel == 19 && previous.vanillaTotal == 500 && previous.xpSetterCalls == 3,
                                "Advancement guard restored XP into an old player session");
                        checkVanilla();
                        waitFor(() -> state(0, 0), this::commands);
                    });
                });
            });
        });
    }

    private static void driftVanilla(Fake target) {
        target.vanillaLevel = 19;
        target.vanillaProgress = .75f;
        target.vanillaTotal = 500;
    }

    private static void resetVanilla(Fake target) {
        // Fixture imitation of an authoritative ledger value; not a plugin API call.
        target.vanillaLevel = 17;
        target.vanillaProgress = .5f;
        target.vanillaTotal = 321;
    }

    private void vanillaLock() {
        var gain = new PlayerExpChangeEvent(player.player, 25);
        Bukkit.getPluginManager().callEvent(gain);
        check(gain.getAmount() == 0, "Default vanilla XP lock allowed natural XP gain");
        DamageSource source = (DamageSource) Proxy.newProxyInstance(DamageSource.class.getClassLoader(),
                new Class<?>[]{DamageSource.class}, (proxy, method, args) -> defaultValue(method.getReturnType()));
        var death = new PlayerDeathEvent(player.player, source, new ArrayList<>(), 42, Component.empty(), true);
        Bukkit.getPluginManager().callEvent(death);
        check(death.getKeepLevel() && death.getDroppedExp() == 0 && !death.shouldDropExperience(),
                "Default vanilla XP lock allowed death XP loss or drops");
        check(!death.getKeepInventory(), "XP lock changed item death rules");
        ExperienceOrb orb = (ExperienceOrb) Proxy.newProxyInstance(ExperienceOrb.class.getClassLoader(),
                new Class<?>[]{ExperienceOrb.class}, (proxy, method, args) -> defaultValue(method.getReturnType()));
        var mend = new PlayerItemMendEvent(player.player, new ItemStack(Material.DIAMOND_PICKAXE), EquipmentSlot.HAND, orb, 8, 4);
        Bukkit.getPluginManager().callEvent(mend);
        check(mend.isCancelled(), "Default vanilla XP lock allowed mending");
        InventoryView view = (InventoryView) Proxy.newProxyInstance(InventoryView.class.getClassLoader(),
                new Class<?>[]{InventoryView.class}, (proxy, method, args) -> method.getName().equals("getPlayer")
                        ? player.player : defaultValue(method.getReturnType()));
        var enchant = new EnchantItemEvent(player.player, view, Bukkit.getWorlds().getFirst().getSpawnLocation().getBlock(),
                new ItemStack(Material.DIAMOND_PICKAXE), 30, java.util.Map.of(), Enchantment.EFFICIENCY, 1, 0);
        Bukkit.getPluginManager().callEvent(enchant);
        check(enchant.isCancelled(), "Default vanilla XP lock allowed survival enchanting");
        var topInventory = Bukkit.createInventory(null, 9);
        AnvilView anvilView = (AnvilView) Proxy.newProxyInstance(AnvilView.class.getClassLoader(),
                new Class<?>[]{AnvilView.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getPlayer" -> player.player;
                    case "getTopInventory" -> topInventory;
                    case "getRepairCost" -> 4;
                    case "convertSlot" -> args[0];
                    default -> defaultValue(method.getReturnType());
                });
        var anvil = new InventoryClickEvent(anvilView, InventoryType.SlotType.RESULT, 2, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        Bukkit.getPluginManager().callEvent(anvil);
        check(anvil.isCancelled(), "Default vanilla XP lock allowed charged anvil output");
        player.gameMode = GameMode.CREATIVE;
        var creative = new InventoryClickEvent(anvilView, InventoryType.SlotType.RESULT, 2, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        Bukkit.getPluginManager().callEvent(creative);
        check(!creative.isCancelled(), "Vanilla XP lock blocked a free creative anvil action");
        player.gameMode = GameMode.SURVIVAL;
        checkProgress(0, 0);
        checkVanilla();
    }

    private void awarded() {
        check(money() == 300.0, "Two level rewards must each pay 150 Credits");
        for (int i = 0; i < 2; i++) {
            String[] args = rewards.get(i);
            check(args.length == 3 && args[0].equals("ProbeUser") && args[1].equals(Integer.toString(i + 1))
                    && Double.parseDouble(args[2]) == 150.0, "Console reward tokens/order differ");
        }
        check("2|50".equals(placeholders()), "Award placeholders differ");
        player.displayCalls = 0;
        core.refresh(player.player);
        check(player.displayCalls > 0 && player.shownLevel == 2 && player.shownProgress == .5f,
                "XP display did not show game level/progress");
        checkVanilla();
        consoleMessages.clear();
        execute("gamexp", console, "view", player.id.toString());
        waitFor(() -> !consoleMessages.isEmpty(), () -> {
            checkProgress(2, 50);
            execute("gamexp", console, "take", player.id.toString(), "999");
            waitFor(() -> state(0, 0), this::taken);
        });
    }

    private void taken() {
        check("0|0".equals(placeholders()), "Taking XP below zero produced wrong placeholders");
        check(deposits() == 2 && rewards.size() == 2, "Taking XP awarded another reward");
        execute("gamexp", console, "give", player.id.toString(), "50");
        waitFor(() -> state(0, 50), () -> {
            execute("gamexp", console, "setlevel", player.id.toString(), "7");
            waitFor(() -> state(7, 0), this::setLevel);
        });
    }

    private void setLevel() {
        check("7|0".equals(placeholders()), "setlevel did not reset game XP");
        checkVanilla();
        execute("gamexp", console, "give", player.id.toString(), "-1");
        execute("gamexp", console, "take", player.id.toString(), "-1");
        execute("gamexp", console, "setlevel", player.id.toString(), "-1");
        after(5, () -> {
            checkProgress(7, 0);
            player.displayCalls = 0;
            readiness(false);
            core.refresh(player.player);
            check(player.displayCalls == 0, "Inventory Sync loading did not gate the XP overlay");
            readiness(true);
            core.refresh(player.player);
            check(player.displayCalls > 0 && player.shownLevel == 7 && player.shownProgress == 0f,
                    "Ready inventory did not resume the XP overlay");
            checkVanilla();
            player.online = false;
            core.quit(new PlayerQuitEvent(player.player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
            waitFor(() -> core.cachedProgress(player.id) == null, () -> {
                player.online = true;
                core.join(new PlayerJoinEvent(player.player, Component.empty()));
                waitFor(() -> state(7, 0), this::declinedReward);
            });
        });
    }

    private void declinedReward() {
        setVault("decline", true);
        execute("gamexp", console, "give", player.id.toString(), "100");
        waitFor(() -> state(8, 0) && attempts() == 3, () -> {
            check(money() == 300 && deposits() == 2 && rewards.size() == 2,
                    "Declined money payment changed balance or ran console rewards");
            consoleMessages.clear();
            execute("gamexp", console, "rewardstatus", player.id.toString());
            waitFor(() -> consoleMessages.stream().anyMatch(line -> line.startsWith("Level 8: REVIEW")), () -> {
                String record = consoleMessages.stream().filter(line -> line.startsWith("Level 8: REVIEW")).findFirst().orElseThrow();
                var match = Pattern.compile("claim ([0-9a-f-]{36})").matcher(record);
                check(match.find(), "Review status did not include its claim UUID");
                declinedClaim = match.group(1);
                setVault("decline", false);
                // A later data refresh must not automatically repeat an uncertain payment.
                after(105, () -> {
                    check(attempts() == 3 && deposits() == 2 && rewards.size() == 2,
                            "Declined reward was automatically retried after provider recovery");
                    unavailableReward();
                });
            });
        });
    }

    private void unavailableReward() {
        economyAvailable(false);
        execute("gamexp", console, "give", player.id.toString(), "100");
        waitFor(() -> state(9, 0), () -> after(8, () -> {
            check(attempts() == 3 && deposits() == 2 && rewards.size() == 2,
                    "Unavailable economy attempted or acknowledged a payment");
            economyAvailable(true);
            waitFor(() -> deposits() == 3 && rewards.size() == 3, () -> {
                check(money() == 450 && attempts() == 4, "Recovered economy paid the queued reward incorrectly");
                consoleMessages.clear();
                execute("gamexp", console, "ackreward", player.id.toString(), declinedClaim);
                waitFor(() -> consoleMessages.stream().anyMatch(line -> line.startsWith("Reward acknowledged.")), () -> {
                    consoleMessages.clear();
                    execute("gamexp", console, "rewardstatus", player.id.toString());
                    waitFor(() -> consoleMessages.contains("Rewards requiring review: 0"), () -> {
                        check(money() == 450 && attempts() == 4 && rewards.size() == 3,
                                "Acknowledgement repeated external reward effects");
                        finish();
                    });
                });
            });
        }));
    }

    private void finish() {
        checkVanilla();
        Bukkit.getPluginManager().disablePlugin(core);
        check(player.shownLevel == 17 && player.shownProgress == .5f,
                "Disabling the overlay did not restore the current vanilla XP display");
        checkVanilla();
        finished = true;
        getLogger().info("CORE_PROBE_OK: real Paper startup, default vanilla XP lock events, coalesced advancement XP restoration and readiness/session fencing, unprivileged level/levels, staff permission, give/take/setlevel/view, per-level Vault and console rewards, readiness-gated payouts, declined/absent economy recovery, manual acknowledgement, actual PlaceholderAPI, rejoin persistence and preserved vanilla XP");
        Bukkit.shutdown();
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        check(Bukkit.isPrimaryThread(), "Reward command ran off main thread");
        check(!(sender instanceof Player), "Reward command did not run as console");
        rewards.add(args.clone());
        return true;
    }

    private void execute(String name, CommandSender sender, String... args) {
        check(Bukkit.isPrimaryThread(), "Probe command ran off main thread");
        PluginCommand command = Bukkit.getPluginCommand(name);
        check(command != null && command.getPlugin() == core, "Missing command /" + name);
        command.execute(sender, name, args);
    }

    private Progress progress() { return core.cachedProgress(player.id); }
    private boolean state(int level, int xp) { Progress value = progress(); return value != null && value.level() == level && value.experience() == xp; }
    private void checkProgress(int level, int xp) { check(state(level, xp), "Unexpected progress: " + progress()); }
    private String placeholders() { return PlaceholderAPI.setPlaceholders(player.player, "%planetry_level%|%planetry_gamexp%"); }
    private double money() { return ((Number) field(vault, "balance")).doubleValue(); }
    private int deposits() { return ((Number) field(vault, "deposits")).intValue(); }
    private int attempts() { return ((Number) field(vault, "attempts")).intValue(); }
    private void checkVanilla() {
        check(player.player.getLevel() == 17 && player.player.getExp() == .5f && player.player.getTotalExperience() == 321,
                "PlanetryCore changed real vanilla experience");
        check(player.xpSetterCalls == player.expectedXpWrites, "Unexpected vanilla XP writes outside advancement restoration");
    }
    private void readiness(boolean ready) {
        try { inventory.getClass().getField("ready").setBoolean(inventory, ready); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private void setVault(String name, boolean value) {
        try { vault.getClass().getField(name).setBoolean(vault, value); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private void economyAvailable(boolean available) {
        try { vault.getClass().getMethod("setAvailable", boolean.class).invoke(vault, available); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private static Object field(Object target, String name) {
        try { Field value = target.getClass().getField(name); return value.get(target); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private void after(long ticks, Runnable next) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try { if (!finished) next.run(); } catch (Throwable error) { fail(error); }
        }, ticks);
    }
    private void waitFor(BooleanSupplier test, Runnable next) {
        new BukkitRunnable() {
            int ticks;
            @Override public void run() {
                try {
                    if (finished) { cancel(); return; }
                    if (test.getAsBoolean()) { cancel(); next.run(); }
                    else if (++ticks > 400) { cancel(); fail(new IllegalStateException("Probe step timed out")); }
                } catch (Throwable error) { cancel(); fail(error); }
            }
        }.runTaskTimer(this, 1, 1);
    }
    private void fail(Throwable error) {
        if (finished) return;
        finished = true;
        getLogger().severe("CORE_PROBE_FAILED: " + error);
        error.printStackTrace();
        Bukkit.shutdown();
    }
    private static void check(boolean condition, String reason) { if (!condition) throw new IllegalStateException(reason); }

    private static CommandSender sender(String name, boolean allowed, List<String> messages) {
        return (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(),
                new Class<?>[]{CommandSender.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getServer" -> Bukkit.getServer();
                    case "hasPermission", "isPermissionSet", "isOp" -> allowed;
                    case "sendMessage" -> { append(messages, args); yield null; }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> name;
                    default -> defaultValue(method.getReturnType());
                });
    }
    private static void append(List<String> messages, Object[] args) {
        if (args == null) return;
        for (Object arg : args) {
            if (arg instanceof String[] strings) messages.addAll(List.of(strings));
            else if (arg instanceof Component component) messages.add(PlainTextComponentSerializer.plainText().serialize(component));
            else if (arg instanceof String string) messages.add(string);
        }
    }
    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == char.class) return (char) 0;
        return null;
    }

    private static final class Fake {
        final UUID id;
        final List<String> messages = new ArrayList<>();
        final Player player;
        boolean online = true;
        GameMode gameMode = GameMode.SURVIVAL;
        float shownProgress = .5f;
        int shownLevel = 17;
        int displayCalls;
        int vanillaLevel = 17;
        float vanillaProgress = .5f;
        int vanillaTotal = 321;
        boolean allowXpRestore;
        int xpSetterCalls;
        int expectedXpWrites;

        Fake() { this(UUID.randomUUID()); }
        Fake(UUID id) {
            this.id = id;
            player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> id;
                        case "getName" -> "ProbeUser";
                        case "getServer" -> Bukkit.getServer();
                        case "getGameMode" -> gameMode;
                        case "isOnline" -> online;
                        case "isValid", "hasPlayedBefore" -> true;
                        case "isDead", "hasPermission", "isPermissionSet", "isOp" -> false;
                        case "getLevel" -> vanillaLevel;
                        case "getExp" -> vanillaProgress;
                        case "getTotalExperience" -> vanillaTotal;
                        case "setLevel" -> {
                            check(allowXpRestore && (int) args[0] == 17, "Unexpected vanilla level write");
                            vanillaLevel = (int) args[0]; xpSetterCalls++; yield null;
                        }
                        case "setExp" -> {
                            check(allowXpRestore && (float) args[0] == .5f, "Unexpected vanilla XP progress write");
                            vanillaProgress = (float) args[0]; xpSetterCalls++; yield null;
                        }
                        case "setTotalExperience" -> {
                            check(allowXpRestore && (int) args[0] == 321, "Unexpected vanilla total XP write");
                            vanillaTotal = (int) args[0]; xpSetterCalls++; yield null;
                        }
                        case "giveExp", "giveExpLevels", "setExperienceLevelAndProgress" ->
                                throw new IllegalStateException("Core called vanilla XP mutator " + method.getName());
                        case "sendExperienceChange" -> {
                            check(Bukkit.isPrimaryThread(), "XP display sent off main thread");
                            shownProgress = (float) args[0];
                            if (args.length > 1) shownLevel = (int) args[1];
                            displayCalls++;
                            yield null;
                        }
                        case "sendMessage" -> { append(messages, args); yield null; }
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "CoreProbe[" + id + "]";
                        default -> defaultValue(method.getReturnType());
                    });
        }
    }
}
