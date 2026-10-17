package dev.flint.planetry.core;

import dev.flint.planetry.core.progression.*;
import dev.flint.planetry.core.storage.*;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class PlanetryCore extends JavaPlugin implements Listener, TabCompleter {
    private static final String PERMISSION = "planetry.adam.gamexp";
    private static final LegacyComponentSerializer TEXT = LegacyComponentSerializer.legacyAmpersand();
    private final Map<UUID, Progress> cache = new ConcurrentHashMap<>();
    // Only touched on the server thread. Object identity fences callbacks from old sessions.
    private final Map<UUID, Player> online = new HashMap<>();
    private final Map<UUID, Player> loading = new HashMap<>();
    private final Set<UUID> rewarding = new HashSet<>();
    private final Map<UUID, VanillaSnapshot> advancementSnapshots = new HashMap<>();
    private record VanillaSnapshot(Player player, int level, int total, float progress) { }
    private ProgressStore store;
    private ThreadPoolExecutor worker;
    private Settings settings;
    private PlanetryExpansion expansion;
    private MailService mail;
    private volatile boolean stopping;
    private long nextRewardWarning;

    @Override public void onEnable() {
        try {
            saveDefaultConfig(); settings = Settings.read(getConfig());
            store = Settings.openStore(getConfig(), getDataFolder().toPath());
            worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1024), task -> {
                Thread thread = new Thread(task, "PlanetryCore-storage"); thread.setDaemon(true); return thread;
            }) {
                @Override protected void terminated() {
                    try { store.close(); }
                    catch (Exception error) { getLogger().severe("Storage close failed"); }
                }
            };
            mail = new MailService(this, Settings.openMailStore(getConfig(), getDataFolder().toPath()),
                    settings.serverId(), this::inventoryReady);
            getServer().getPluginManager().registerEvents(this, this);
            getServer().getPluginManager().registerEvents(new VanillaExperienceGuard(settings.lockVanillaExperience(),
                    player -> message(player, "vanilla-xp-locked", Map.of())), this);
            Objects.requireNonNull(getCommand("level")).setExecutor(this);
            Objects.requireNonNull(getCommand("gamexp")).setExecutor(this);
            getCommand("gamexp").setTabCompleter(this);
            Objects.requireNonNull(getCommand("mail")).setExecutor(mail);
            Objects.requireNonNull(getCommand("sendmail")).setExecutor(mail);
            getCommand("sendmail").setTabCompleter(mail);
            mail.start();
            if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                expansion = PlanetryExpansion.attach(this);
            }
            getServer().getScheduler().runTaskTimer(this, () -> List.copyOf(online.values()).forEach(this::refresh), 1, settings.refreshTicks());
            getServer().getScheduler().runTaskTimer(this, () -> List.copyOf(online.values()).forEach(this::load), 1, settings.dataRefreshTicks());
            getServer().getOnlinePlayers().forEach(this::track);
            getLogger().info("PlanetryCore enabled: 100 game XP per level; vanilla experience is preserved.");
            if (!canReward()) warnRewards();
        } catch (Exception | LinkageError error) {
            getLogger().log(Level.SEVERE, "PlanetryCore could not start. Check configuration and storage.", error);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override public void onDisable() {
        stopping = true;
        if (mail != null) mail.close();
        if (expansion != null) expansion.detach();
        for (Player player : online.values()) if (player.isOnline()) player.sendExperienceChange(player.getExp(), player.getLevel());
        online.clear(); cache.clear(); advancementSnapshots.clear();
        if (worker != null) {
            // The termination hook closes storage after all accepted operations, even with a full queue.
            worker.shutdown();
            try { if (!worker.awaitTermination(30, TimeUnit.SECONDS)) getLogger().warning("Storage is still finishing accepted operations."); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        } else if (store != null) try { store.close(); } catch (Exception ignored) { }
    }

    public Progress cachedProgress(UUID id) { return cache.get(id); }
    @EventHandler public void join(PlayerJoinEvent event) { track(event.getPlayer()); }
    private void track(Player player) { online.put(player.getUniqueId(), player); load(player); mail.track(player); }
    @EventHandler public void quit(PlayerQuitEvent event) {
        if (mail != null) mail.quit(event.getPlayer());
        UUID id = event.getPlayer().getUniqueId();
        if (online.get(id) == event.getPlayer()) { online.remove(id); cache.remove(id); advancementSnapshots.remove(id); }
    }
    @EventHandler(priority = EventPriority.MONITOR) public void respawn(PlayerRespawnEvent event) { refreshLater(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR) public void experience(PlayerExpChangeEvent event) { refreshLater(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR) public void levelChange(PlayerLevelChangeEvent event) { refreshLater(event.getPlayer()); }
    @EventHandler(priority = EventPriority.HIGHEST) public void advancement(PlayerAdvancementDoneEvent event) {
        Player player = event.getPlayer();
        if (!settings.lockVanillaExperience() || !current(player) || player.isDead() || !inventoryReady(player.getUniqueId())) return;
        // Paper fires this before advancement rewards, whose XP bypasses PlayerExpChangeEvent.
        UUID id = player.getUniqueId();
        VanillaSnapshot pending = advancementSnapshots.get(id);
        if (pending != null && pending.player() == player) return;
        var snapshot = new VanillaSnapshot(player, player.getLevel(), player.getTotalExperience(), player.getExp());
        advancementSnapshots.put(id, snapshot);
        getServer().getScheduler().runTask(this, () -> {
            if (!advancementSnapshots.remove(id, snapshot)) return;
            // Never restore across a reconnect, death screen, or an inventory authority transition.
            if (!current(player) || player.isDead() || !inventoryReady(id)) return;
            if (player.getLevel() != snapshot.level() || player.getExp() != snapshot.progress() || player.getTotalExperience() != snapshot.total()) {
                player.setLevel(snapshot.level()); player.setExp(snapshot.progress()); player.setTotalExperience(snapshot.total());
            }
            refresh(player);
        });
    }
    private void refreshLater(Player player) { getServer().getScheduler().runTask(this, () -> refresh(player)); }

    private boolean current(Player player) { return !stopping && player.isOnline() && online.get(player.getUniqueId()) == player; }
    private void load(Player player) {
        UUID id = player.getUniqueId();
        if (!current(player) || loading.get(id) == player) return;
        loading.put(id, player);
        submit(() -> store.get(id), progress -> {
            loading.remove(id, player);
            if (current(player)) { cache.put(id, progress); refresh(player); drainRewards(player); }
        }, error -> { loading.remove(id, player); if (current(player)) cache.remove(id); });
    }

    /** Sends display packets only. Never calls setLevel, setExp or setTotalExperience. */
    public void refresh(Player player) {
        if (!current(player) || !settings.xpBar() || !inventoryReady(player.getUniqueId())) return;
        Progress progress = cache.get(player.getUniqueId());
        if (progress != null) player.sendExperienceChange(progress.experience() / 100.0f, progress.level());
    }

    private boolean inventoryReady(UUID id) {
        Plugin sync = getServer().getPluginManager().getPlugin("NetworkInventorySync");
        if (sync == null) return true;
        if (!sync.isEnabled()) return false;
        try { return Boolean.TRUE.equals(sync.getClass().getMethod("isReady", UUID.class).invoke(sync, id)); }
        catch (ReflectiveOperationException | LinkageError error) { return false; }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (stopping || store == null) { message(sender, "unavailable", Map.of()); return true; }
        if (command.getName().equalsIgnoreCase("level")) {
            if (!(sender instanceof Player player)) { message(sender, "players-only", Map.of()); return true; }
            Progress progress = cache.get(player.getUniqueId());
            if (progress == null) { message(sender, "loading", Map.of()); return true; }
            message(sender, "level", values(player, progress)); message(sender, "hint", Map.of()); return true;
        }
        if (!sender.hasPermission(PERMISSION)) { message(sender, "no-permission", Map.of()); return true; }
        if (args.length < 2) return usage(sender);
        String action = args[0].toLowerCase(Locale.ROOT);
        if (!Set.of("give", "take", "view", "setlevel", "rewardstatus", "ackreward").contains(action)) return usage(sender);
        boolean view = action.equals("view") || action.equals("rewardstatus");
        if (args.length != (view ? 2 : 3)) return usage(sender);
        OfflinePlayer target = findPlayer(args[1]);
        if (target == null) { message(sender, "unknown-player", Map.of()); return true; }
        UUID id = target.getUniqueId();
        if (action.equals("rewardstatus")) {
            submit(() -> store.rewardsNeedingReview(id), records -> {
                sender.sendMessage(TEXT.deserialize("&7Rewards requiring review: " + records.size()));
                for (var record : records) sender.sendMessage(TEXT.deserialize("&7Level " + record.level() + ": " + record.state() + ", claim " + record.claimId() + ", server " + record.serverId()));
            }, error -> unavailable(sender)); return true;
        }
        if (action.equals("ackreward")) {
            if (sender instanceof Player) { sender.sendMessage("Reward acknowledgement is console-only. Check the economy and commands before acknowledging."); return true; }
            UUID claim;
            try { claim = UUID.fromString(args[2]); } catch (IllegalArgumentException e) { return usage(sender); }
            submit(() -> { store.completeReward(id, claim, RewardOutcome.COMPLETE); return true; },
                    ignored -> sender.sendMessage("Reward acknowledged. No money or commands were repeated."), error -> unavailable(sender)); return true;
        }
        if (view) {
            submit(() -> store.get(id), progress -> message(sender, "updated", values(target, progress)), error -> unavailable(sender)); return true;
        }
        long amount;
        try {
            if (!args[2].matches("[0-9]{1,12}")) throw new IllegalArgumentException();
            amount = Long.parseLong(args[2]);
            if (action.equals("give") && amount > Progress.MAX_GIVE) throw new IllegalArgumentException();
            if (action.equals("setlevel") && amount > Progress.MAX_LEVEL) throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) {
            sender.sendMessage("Use a nonnegative whole number. Give is limited to 10000 XP; the maximum level is 1000000."); return true;
        }
        Operation operation = switch (action) { case "give" -> Operation.GIVE; case "take" -> Operation.TAKE; default -> Operation.SET_LEVEL; };
        submit(() -> store.mutate(id, operation, amount), result -> {
            Player player = online.get(id);
            if (player != null && current(player)) { cache.put(id, result.current()); refresh(player); drainRewards(player); }
            message(sender, "updated", values(target, result.current()));
        }, error -> {
            if (error instanceof IllegalArgumentException) sender.sendMessage("That change exceeds the maximum game level.");
            else unavailable(sender);
        });
        return true;
    }

    private OfflinePlayer findPlayer(String value) {
        try {
            UUID id = UUID.fromString(value);
            Player player = online.get(id);
            return player != null ? player : getServer().getOfflinePlayer(id);
        } catch (IllegalArgumentException ignored) { }
        for (Player player : online.values()) if (player.getName().equalsIgnoreCase(value)) return player;
        return getServer().getOfflinePlayerIfCached(value);
    }

    private boolean usage(CommandSender sender) {
        sender.sendMessage("/gamexp give|take|setlevel <player|uuid> <amount>, /gamexp view <player|uuid>");
        sender.sendMessage("/gamexp rewardstatus <player|uuid>, console: /gamexp ackreward <player|uuid> <claim-uuid>"); return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) return List.of();
        List<String> options = args.length == 1 ? List.of("give", "take", "view", "setlevel", "rewardstatus")
                : args.length == 2 ? getServer().getOnlinePlayers().stream().map(Player::getName).toList() : List.of();
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    private boolean canReward() {
        try {
            if (settings.money() > 0 && (!getServer().getPluginManager().isPluginEnabled("Vault") || !VaultRewards.available(getServer()))) return false;
            for (String command : settings.commands())
                if (getServer().getCommandMap().getCommand(command.split(" ", 2)[0]) == null) return false;
            return true;
        } catch (RuntimeException | LinkageError error) { return false; }
    }
    private void warnRewards() {
        if (System.currentTimeMillis() < nextRewardWarning) return;
        nextRewardWarning = System.currentTimeMillis() + 60000;
        getLogger().warning("Level rewards are queued: Vault/economy or a configured reward command is unavailable.");
    }
    private void drainRewards(Player player) {
        UUID id = player.getUniqueId();
        if (!current(player) || player.isDead() || !inventoryReady(id) || rewarding.contains(id)) return;
        if (!canReward()) { warnRewards(); return; }
        if (!player.getName().matches("[A-Za-z0-9_.-]{1,32}")) return;
        rewarding.add(id);
        submit(() -> store.claimNextReward(id, settings.serverId()), claim -> {
            if (claim.isEmpty()) { rewarding.remove(id); return; }
            var reward = claim.get();
            RewardOutcome outcome = RewardOutcome.COMPLETE;
            try {
                if (!current(player) || player.isDead() || !inventoryReady(id))
                    throw new IllegalStateException("Player session or inventory readiness changed after claim");
                if (settings.money() > 0) VaultRewards.deposit(getServer(), player, settings.money());
                Map<String, String> tokens = new HashMap<>(values(player, cache.getOrDefault(id, Progress.initial())));
                tokens.put("level", Integer.toString(reward.level()));
                for (String command : settings.commands())
                    if (!getServer().dispatchCommand(getServer().getConsoleSender(), render(command, tokens)))
                        throw new IllegalStateException("A reward command did not execute");
                message(player, "level-up", tokens); message(player, "reward", tokens);
            } catch (Exception | LinkageError error) {
                outcome = RewardOutcome.REVIEW;
                getLogger().warning("Reward requires operator review: player " + id + ", level " + reward.level() + ", claim " + reward.claimId() + ". External effects may be partial; it will not be retried automatically.");
            }
            RewardOutcome finalOutcome = outcome;
            submit(() -> { store.completeReward(id, reward.claimId(), finalOutcome); return true; }, ignored -> {
                rewarding.remove(id);
                Player active = online.get(id);
                if (active != null) drainRewards(active);
            }, error -> { rewarding.remove(id); getLogger().severe("Reward acknowledgement failed; inspect rewardstatus before repeating any reward."); });
        }, error -> rewarding.remove(id));
    }

    private Map<String, String> values(OfflinePlayer player, Progress progress) {
        return Map.of("player", Objects.toString(player.getName(), player.getUniqueId().toString()),
                "uuid", player.getUniqueId().toString(), "level", Integer.toString(progress.level()),
                "gamexp", Integer.toString(progress.experience()), "money", java.math.BigDecimal.valueOf(settings.money()).stripTrailingZeros().toPlainString(), "currency", settings.currency());
    }
    private static String render(String value, Map<String, String> values) {
        for (var entry : values.entrySet()) value = value.replace("{" + entry.getKey() + "}", entry.getValue());
        return value;
    }
    private void message(CommandSender sender, String key, Map<String, String> values) {
        sender.sendMessage(TEXT.deserialize(render(settings.messages().getOrDefault(key, key), values)));
    }
    private void unavailable(CommandSender sender) { message(sender, "unavailable", Map.of()); }
    @FunctionalInterface private interface Work<T> { T run() throws Exception; }
    private <T> void submit(Work<T> action, Consumer<T> success, Consumer<Exception> failure) {
        try {
            worker.execute(() -> {
                try { T value = action.run(); main(() -> success.accept(value)); }
                catch (Exception error) {
                    if (!(error instanceof IllegalArgumentException)) getLogger().warning("A storage operation failed (" + error.getClass().getSimpleName() + "). Progress was not confirmed.");
                    main(() -> failure.accept(error));
                }
            });
        } catch (RejectedExecutionException error) { failure.accept(error); }
    }
    private void main(Runnable task) {
        if (!stopping) try { getServer().getScheduler().runTask(this, () -> { if (!stopping) task.run(); }); }
        catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { }
    }
}
