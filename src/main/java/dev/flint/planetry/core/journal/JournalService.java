package dev.flint.planetry.core.journal;

import dev.flint.planetry.core.PlanetryCore;
import dev.flint.planetry.core.journal.JournalSettings.Category;
import dev.flint.planetry.core.journal.JournalSettings.Entry;
import dev.flint.planetry.core.journal.storage.JournalStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Main-thread inventory/command lifecycle, serialized bounded storage, and an async-safe PAPI cache. */
public final class JournalService implements Listener, CommandExecutor, TabCompleter, AutoCloseable {
    private final JavaPlugin plugin;
    private final JournalStore store;
    private final JournalSettings settings;
    private final BiFunction<String, String, ItemStack> mmoItems;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final ConcurrentHashMap<UUID, Set<String>> cache = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor worker;
    private final Set<String> missingItems = new HashSet<>();
    private BukkitTask timer;
    private boolean started;
    private volatile boolean closed;

    private static final class Session {
        final Player player;
        final Set<String> pending = new HashSet<>();
        boolean loading;
        Session(Player player) { this.player = player; }
    }
    private record Mutation(boolean changed, Set<String> found) { }
    static final class Menu implements InventoryHolder {
        final UUID viewer;
        final String category;
        final int page;
        private Inventory inventory;
        Menu(UUID viewer, String category, int page) { this.viewer = viewer; this.category = category; this.page = page; }
        @Override public Inventory getInventory() { return Objects.requireNonNull(inventory); }
    }

    public JournalService(PlanetryCore plugin, JournalStore store, BiFunction<String, String, ItemStack> mmoItems)
            throws IOException, InvalidConfigurationException {
        this(plugin, store, loadSettings(plugin, store), mmoItems);
    }

    private static JournalSettings loadSettings(JavaPlugin plugin, JournalStore store) throws IOException, InvalidConfigurationException {
        try { return JournalSettings.load(plugin); }
        catch (IOException | InvalidConfigurationException | RuntimeException | Error error) {
            try { store.close(); } catch (IOException closeFailure) { error.addSuppressed(closeFailure); }
            throw error;
        }
    }

    JournalService(JavaPlugin plugin, JournalStore store, JournalSettings settings, BiFunction<String, String, ItemStack> mmoItems) {
        this.plugin = Objects.requireNonNull(plugin); this.store = Objects.requireNonNull(store);
        this.settings = Objects.requireNonNull(settings); this.mmoItems = Objects.requireNonNull(mmoItems);
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1024), task -> {
            Thread thread = new Thread(task, "planetry-journal-storage"); thread.setDaemon(true); return thread;
        }) {
            @Override protected void terminated() {
                try { store.close(); } catch (IOException error) { plugin.getLogger().severe("Journal storage close failed."); }
            }
        };
    }

    public void start() {
        requireMain();
        if (closed) throw new IllegalStateException("The journal service is closed");
        if (started) return;
        started = true;
        var journal = Objects.requireNonNull(plugin.getCommand("journal"), "journal command registration");
        var admin = Objects.requireNonNull(plugin.getCommand("journaladmin"), "journaladmin command registration");
        journal.setExecutor(this); journal.setTabCompleter(this);
        admin.setExecutor(this); admin.setTabCompleter(this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getOnlinePlayers().forEach(this::track);
        timer = plugin.getServer().getScheduler().runTaskTimer(plugin, this::refresh, 100, 100);
    }

    @EventHandler public void join(PlayerJoinEvent event) { if (running()) track(event.getPlayer()); }
    @EventHandler public void quit(PlayerQuitEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && session.player == event.getPlayer()) {
            sessions.remove(event.getPlayer().getUniqueId()); cache.remove(event.getPlayer().getUniqueId());
        }
    }
    private void track(Player player) {
        Session previous = sessions.get(player.getUniqueId());
        if (previous != null && previous.player == player) return;
        cache.remove(player.getUniqueId());
        var session = new Session(player); sessions.put(player.getUniqueId(), session); load(session);
    }
    private void refresh() { if (running()) List.copyOf(sessions.values()).forEach(this::load); }
    private void load(Session session) {
        if (!current(session) || session.loading) return;
        session.loading = true;
        submit(() -> Set.copyOf(store.found(session.player.getUniqueId())), found -> {
            if (!current(session)) return;
            session.loading = false; cache.put(session.player.getUniqueId(), found); refreshMenu(session);
        }, failure -> {
            if (!current(session)) return;
            session.loading = false; cache.remove(session.player.getUniqueId());
        });
    }

    public void harvest(Player player, String nodeId) {
        requireMain();
        if (player != null && nodeId != null) settings.harvest(nodeId).forEach(entry -> unlock(player, entry));
    }
    public void killed(Player player, String mythicMobId) {
        requireMain();
        if (player != null && mythicMobId != null) settings.kills(mythicMobId).forEach(entry -> unlock(player, entry));
    }
    private void unlock(Player player, Entry entry) {
        Session session = sessions.get(player.getUniqueId());
        if (!current(session) || session.player != player || cache.getOrDefault(player.getUniqueId(), Set.of()).contains(entry.id())
                || !session.pending.add(entry.id())) return;
        mutate(player.getUniqueId(), entry, true, result -> {
            if (!current(session)) return;
            session.pending.remove(entry.id()); cache.put(player.getUniqueId(), result.found());
            if (result.changed()) notifyUnlock(session, entry);
            refreshMenu(session);
        }, error -> { if (current(session)) { session.pending.remove(entry.id()); say(player, "unavailable", Map.of()); } });
    }

    /** All known placeholders avoid Bukkit/storage calls and are safe from async PAPI callers. */
    public String placeholder(UUID playerId, String params) {
        if (params == null || !params.startsWith("journal_")) return null;
        String request = params.substring(8);
        if (request.endsWith("_found")) {
            String id = request.substring(0, request.length() - 6);
            if (!settings.entries().containsKey(id)) return null;
            Set<String> found = playerId == null ? null : cache.get(playerId);
            return found == null ? "" : Boolean.toString(found.contains(id));
        }
        String suffix = request.endsWith("_unlocked") ? "_unlocked" : request.endsWith("_max") ? "_max" : null;
        if (suffix == null) return null;
        Category category = settings.categories().get(request.substring(0, request.length() - suffix.length()));
        if (category == null) return null;
        Set<String> found = playerId == null ? null : cache.get(playerId);
        if (found == null) return "";
        return suffix.equals("_max") ? Integer.toString(category.entries().size())
                : Long.toString(category.entries().stream().filter(entry -> found.contains(entry.id())).count());
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        requireMain();
        if (!running()) { say(sender, "unavailable", Map.of()); return true; }
        if (command != null && command.getName().equalsIgnoreCase("journaladmin")) return admin(sender, args);
        if (args.length > 0 && args[0].equalsIgnoreCase("open")) {
            if (!allowed(sender)) { say(sender, "denied", Map.of()); return true; }
            if (args.length != 3) { say(sender, "usage", Map.of()); return true; }
            Player target = online(args[1]);
            if (target == null) { say(sender, "unknown-player", Map.of()); return true; }
            open(target, args[2].toLowerCase(Locale.ROOT), 0); return true;
        }
        if (!(sender instanceof Player player)) { say(sender, "player-only", Map.of()); return true; }
        if (args.length > 1) { say(sender, "usage", Map.of()); return true; }
        String category = args.length == 0 ? null : args[0].toLowerCase(Locale.ROOT);
        // The only console action exposed to a public player is opening their own validated category.
        if (category != null && settings.categories().containsKey(category)) {
            Objects.requireNonNull(plugin.getCommand("journal")).execute(plugin.getServer().getConsoleSender(), "journal",
                    new String[]{"open", player.getUniqueId().toString(), category});
        } else open(player, category, 0);
        return true;
    }

    private boolean admin(CommandSender sender, String[] args) {
        if (!allowed(sender)) { say(sender, "denied", Map.of()); return true; }
        if (args.length != 3 || !(args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove"))) {
            say(sender, "admin-usage", Map.of()); return true;
        }
        Entry entry = settings.entries().get(args[2]);
        if (entry == null) { say(sender, "unknown-entry", Map.of("id", args[2])); return true; }
        OfflinePlayer target = target(args[0]);
        if (target == null) { say(sender, "unknown-player", Map.of()); return true; }
        UUID id = target.getUniqueId();
        String name = target.getName() == null ? id.toString() : target.getName();
        boolean add = args[1].equalsIgnoreCase("add");
        Session targetSession = sessions.get(id);
        Session senderSession = sender instanceof Player player ? sessions.get(player.getUniqueId()) : null;
        mutate(id, entry, add, result -> {
            // Both effects are fenced to the sessions that existed when the staff command ran.
            if (current(targetSession)) {
                cache.put(id, result.found());
                if (add && result.changed()) notifyUnlock(targetSession, entry);
                refreshMenu(targetSession);
            }
            if (!(sender instanceof Player) || current(senderSession))
                say(sender, result.changed() ? add ? "added" : "removed" : "unchanged", Map.of("id", entry.id(), "player", name));
        }, error -> { if (!(sender instanceof Player) || current(senderSession)) say(sender, "unavailable", Map.of()); });
        return true;
    }

    static boolean allowed(CommandSender sender) { return sender instanceof ConsoleCommandSender || sender.hasPermission("planetry.admin.journal"); }
    private Player online(String value) {
        UUID id = uuid(value);
        if (id != null) {
            Session session = sessions.get(id);
            if (current(session)) return session.player;
        } else for (Session session : sessions.values()) {
            if (current(session) && session.player.getName().equals(value)) return session.player;
        }
        return id == null ? plugin.getServer().getPlayerExact(value) : plugin.getServer().getPlayer(id);
    }
    private OfflinePlayer target(String value) {
        UUID id = uuid(value);
        if (id != null) return plugin.getServer().getOfflinePlayer(id);
        if (!value.matches("[A-Za-z0-9_]{1,16}")) return null;
        Player online = plugin.getServer().getPlayerExact(value);
        return online == null ? plugin.getServer().getOfflinePlayerIfCached(value) : online;
    }
    private static UUID uuid(String value) {
        try { UUID id = UUID.fromString(value); return id.toString().equalsIgnoreCase(value) ? id : null; }
        catch (IllegalArgumentException ignored) { return null; }
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> choices = List.of();
        boolean admin = command.getName().equalsIgnoreCase("journaladmin");
        if (admin && !allowed(sender)) return List.of();
        if (admin) choices = switch (args.length) {
            case 1 -> plugin.getServer().getOnlinePlayers().stream().map(Player::getName).toList();
            case 2 -> List.of("add", "remove");
            case 3 -> settings.entries().keySet().stream().sorted().toList();
            default -> List.of();
        };
        else if (args.length == 1) {
            choices = new ArrayList<>(settings.categories().keySet());
            if (allowed(sender)) choices.add("open");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("open") && allowed(sender))
            choices = plugin.getServer().getOnlinePlayers().stream().map(Player::getName).toList();
        else if (args.length == 3 && args[0].equalsIgnoreCase("open") && allowed(sender)) choices = List.copyOf(settings.categories().keySet());
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    private void open(Player player, String categoryId, int requestedPage) {
        Session session = sessions.get(player.getUniqueId());
        if (!current(session) || session.player != player) return;
        if (!cache.containsKey(player.getUniqueId())) { say(player, "loading", Map.of()); load(session); return; }
        Category category = categoryId == null ? null : settings.categories().get(categoryId);
        if (categoryId != null && category == null) { say(player, "unknown-category", Map.of("category", categoryId)); return; }
        int page = category == null ? 0 : JournalSettings.page(requestedPage, category.entries().size());
        var menu = new Menu(player.getUniqueId(), categoryId, page);
        Component title = category == null ? text("title", Map.of()) : text("category-title", Map.of("category", category.name(),
                "page", Integer.toString(page + 1), "pages", Integer.toString(JournalSettings.pages(category.entries().size()))));
        menu.inventory = plugin.getServer().createInventory(menu, category == null ? 45 : 54, title);
        Set<String> found = cache.get(player.getUniqueId());
        if (category == null) {
            int[] slots = {10, 12, 14, 16, 28, 30, 32};
            int index = 0;
            for (Category value : settings.categories().values()) menu.inventory.setItem(slots[index++], icon(value.material(),
                    JournalText.render(value.name(), Map.of(), Map.of()), List.of(text("category-lore", counts(value, found)))));
        } else {
            int offset = page * 45, end = Math.min(offset + 45, category.entries().size());
            for (int index = offset; index < end; index++) {
                Entry entry = category.entries().get(index);
                menu.inventory.setItem(index - offset, entryIcon(entry, found.contains(entry.id())));
            }
            if (category.entries().isEmpty()) menu.inventory.setItem(22, icon("PAPER", text("empty", Map.of()), List.of()));
            menu.inventory.setItem(49, icon("BOOK", text("back", Map.of()), List.of()));
            if (page > 0) menu.inventory.setItem(45, icon("ARROW", text("previous", Map.of()), List.of()));
            if (page + 1 < JournalSettings.pages(category.entries().size())) menu.inventory.setItem(53, icon("ARROW", text("next", Map.of()), List.of()));
        }
        player.openInventory(menu.inventory);
    }

    private ItemStack entryIcon(Entry entry, boolean found) {
        if (!found) return icon("GRAY_DYE", text("locked-name", Map.of("id", entry.id())), List.of(text("locked-lore", Map.of())));
        ItemStack item = build(entry);
        if (item == null) return missing(entry);
        var meta = item.getItemMeta();
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(text("found-lore", Map.of())); meta.lore(lore); item.setItemMeta(meta);
        return item;
    }
    private ItemStack missing(Entry entry) {
        return icon("BARRIER", text("missing-name", Map.of("id", entry.id())),
                List.of(text("missing-lore", Map.of("type", entry.type(), "item", entry.item()))));
    }
    private ItemStack build(Entry entry) {
        try {
            ItemStack item = mmoItems.apply(entry.type(), entry.item());
            if (item != null && item.getAmount() > 0 && !item.getType().isAir()) {
                ItemStack copy = item.clone(); copy.setAmount(1); return copy;
            }
        } catch (RuntimeException | LinkageError error) { /* optional integration has no authority over journal persistence */ }
        if (missingItems.add(entry.id())) plugin.getLogger().warning("Journal entry '" + entry.id() + "' has no available MMOItems definition (" + entry.type() + "/" + entry.item() + ").");
        return null;
    }
    private ItemStack icon(String material, Component name, List<Component> lore) {
        Material type = Material.matchMaterial(material);
        if (type == null || !type.isItem() || type.isAir()) type = Material.PAPER;
        ItemStack item = ItemStack.of(type);
        var meta = item.getItemMeta(); meta.displayName(name); meta.lore(lore); item.setItemMeta(meta); return item;
    }

    @EventHandler(priority = EventPriority.HIGHEST) public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        // Cancel every action in this view, including bottom shift/double/number-key/creative clicks.
        event.setCancelled(true);
        if (!running() || !(event.getWhoClicked() instanceof Player player) || !menu.viewer.equals(player.getUniqueId())
                || event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        int slot = event.getRawSlot();
        if (menu.category == null) {
            int[] slots = {10, 12, 14, 16, 28, 30, 32};
            for (int index = 0; index < slots.length; index++) if (slot == slots[index]) {
                String category = JournalSettings.CATEGORY_IDS.get(index); later(() -> open(player, category, 0)); return;
            }
        } else {
            String category = menu.category;
            if (slot == 49) later(() -> open(player, null, 0));
            else if (slot == 45 && menu.page > 0) later(() -> open(player, category, menu.page - 1));
            else if (slot == 53 && menu.page + 1 < JournalSettings.pages(settings.categories().get(category).entries().size()))
                later(() -> open(player, category, menu.page + 1));
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) event.setCancelled(true);
    }
    private void refreshMenu(Session session) {
        var view = session.player.getOpenInventory();
        if (view != null && view.getTopInventory().getHolder() instanceof Menu menu)
            open(session.player, menu.category, menu.page);
    }

    private void notifyUnlock(Session session, Entry entry) {
        if (settings.unlockMessage().isEmpty()) return;
        Category category = settings.categories().get(entry.category());
        Map<String, String> values = new HashMap<>(counts(category, cache.getOrDefault(session.player.getUniqueId(), Set.of())));
        values.put("id", entry.id()); values.put("player", session.player.getName()); values.put("category", category.name());
        ItemStack item = build(entry);
        if (item == null) item = missing(entry);
        var meta = item.getItemMeta();
        Component name = meta.displayName() == null ? Component.text(entry.id()) : meta.displayName();
        // SHOW_ITEM includes the actual MMOItems metadata/lore; item names retain their original component formatting.
        name = name.hoverEvent(item.asHoverEvent());
        Component link = text("open", values).hoverEvent(text("open-hover", values))
                .clickEvent(ClickEvent.runCommand("/journal " + category.id()));
        for (String line : settings.unlockMessage())
            session.player.sendMessage(JournalText.render(line, values, Map.of("item", name, "open", link)));
    }
    private Map<String, String> counts(Category category, Set<String> found) {
        return Map.of("unlocked", Long.toString(category.entries().stream().filter(entry -> found.contains(entry.id())).count()),
                "max", Integer.toString(category.entries().size()));
    }
    private Component text(String key, Map<String, String> values) { return JournalText.render(settings.messages().getOrDefault(key, key), values, Map.of()); }
    private void say(CommandSender sender, String key, Map<String, String> values) { sender.sendMessage(text(key, values)); }
    private boolean current(Session session) { return running() && session != null && sessions.get(session.player.getUniqueId()) == session && session.player.isOnline(); }
    private boolean running() { return started && !closed; }
    private void requireMain() { if (!plugin.getServer().isPrimaryThread()) throw new IllegalStateException("Journal lifecycle and inventory access require the server thread"); }
    private void mutate(UUID id, Entry entry, boolean found, Consumer<Mutation> success, Consumer<Exception> failure) {
        submit(() -> new Mutation(store.setFound(id, entry.id(), found), Set.copyOf(store.found(id))), success, failure);
    }
    @FunctionalInterface private interface Work<T> { T run() throws Exception; }
    private <T> void submit(Work<T> work, Consumer<T> success, Consumer<Exception> failure) {
        try {
            worker.execute(() -> {
                try { T result = work.run(); later(() -> success.accept(result)); }
                catch (Exception error) {
                    plugin.getLogger().warning("Journal storage did not confirm an operation (" + error.getClass().getSimpleName() + ").");
                    later(() -> failure.accept(error));
                }
            });
        } catch (RejectedExecutionException error) { failure.accept(error); }
    }
    private void later(Runnable action) {
        if (!closed) try { plugin.getServer().getScheduler().runTask(plugin, () -> { if (!closed) action.run(); }); }
        catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { }
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        if (timer != null) timer.cancel();
        // Remove display inventories before unregistering their extraction protection.
        for (Session session : sessions.values()) {
            var view = session.player.getOpenInventory();
            if (view != null && view.getTopInventory().getHolder() instanceof Menu) session.player.closeInventory();
        }
        HandlerList.unregisterAll(this);
        sessions.clear(); cache.clear(); worker.shutdown();
        try {
            if (!worker.awaitTermination(10, TimeUnit.SECONDS)) plugin.getLogger().warning("Journal storage is still finishing accepted operations.");
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }
}
