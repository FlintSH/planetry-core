package dev.flint.planetry.core;

import dev.flint.planetry.core.mail.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Bukkit access stays on the server thread; durable mail operations use a bounded worker. */
final class MailService implements CommandExecutor, TabCompleter, AutoCloseable {
    static final String PERMISSION = "planetry.admin.sendmail";
    private static final LegacyComponentSerializer TEXT = LegacyComponentSerializer.legacyAmpersand();
    private static final int BATCH = 50;
    private final PlanetryCore plugin;
    private final MailStore store;
    private final String serverId;
    private final Predicate<UUID> inventoryReady;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Set<UUID> claiming = new HashSet<>();
    private final ThreadPoolExecutor worker;
    private BukkitTask timer;
    private volatile boolean stopping;
    private boolean polling;

    private static final class Session {
        final Player player;
        final UUID id;
        final String name;
        final UUID token = UUID.randomUUID();
        final Set<UUID> notified = new HashSet<>();
        boolean registered;
        boolean warnedMissing;
        MailRecord cursor;
        Session(Player player) { this.player = player; this.id = player.getUniqueId(); this.name = player.getName(); }
    }

    MailService(PlanetryCore plugin, MailStore store, String serverId, Predicate<UUID> inventoryReady) {
        this.plugin = plugin; this.store = store; this.serverId = serverId; this.inventoryReady = inventoryReady;
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1024), task -> {
            Thread thread = new Thread(task, "PlanetryCore-mail"); thread.setDaemon(true); return thread;
        }) {
            @Override protected void terminated() {
                try { store.close(); } catch (Exception error) { plugin.getLogger().severe("Mail storage close failed."); }
            }
        };
    }

    void start() { timer = plugin.getServer().getScheduler().runTaskTimer(plugin, this::poll, 1, 100); }
    void track(Player player) {
        Session session = new Session(player);
        sessions.put(player.getUniqueId(), session);
        submit(() -> { store.register(session.id, session.name, session.token); return store.pending(session.id, BATCH); },
                pending -> { if (current(session)) { session.registered = true; notifyMail(session, pending); } }, error -> { });
    }
    void quit(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.player != player) return;
        sessions.remove(player.getUniqueId());
        submit(() -> { store.disconnect(session.id, session.token); return true; }, ignored -> { }, error -> { });
    }
    private boolean current(Session session) {
        return !stopping && session.player.isOnline() && sessions.get(session.id) == session;
    }
    private boolean ready(Session session) {
        return current(session) && !session.player.isDead() && inventoryReady.test(session.id);
    }
    private void poll() {
        if (stopping || polling) return;
        List<Session> snapshot = sessions.values().stream().filter(this::current).toList();
        // Capture Bukkit names on the main thread, even when registration needs a retry.
        Map<UUID, String> names = new HashMap<>();
        Map<UUID, UUID> tokens = new HashMap<>();
        for (Session session : snapshot) {
            names.put(session.id, session.player.getName());
            tokens.put(session.id, session.token);
        }
        Set<Session> unregistered = new HashSet<>();
        for (Session session : snapshot) if (!session.registered) unregistered.add(session);
        polling = true;
        submit(() -> {
            for (Session session : snapshot) if (unregistered.contains(session)) {
                UUID id = session.id;
                store.register(id, names.get(id), session.token);
            }
            store.heartbeat(tokens);
            Map<Session, List<MailRecord>> result = new LinkedHashMap<>();
            for (Session session : snapshot) result.put(session, store.pending(session.id, BATCH));
            return result;
        }, result -> {
            polling = false;
            result.forEach((session, pending) -> { if (current(session)) { session.registered = true; notifyMail(session, pending); } });
        }, error -> polling = false);
    }
    private void notifyMail(Session session, List<MailRecord> pending) {
        if (!current(session)) return;
        Set<UUID> visible = new HashSet<>();
        for (MailRecord mail : pending) {
            visible.add(mail.id());
            if (session.notified.add(mail.id())) {
                Component notice = text("notice", "&d❕ {sender}&7 has sent you a gift!", Map.of("sender", mail.sender()));
                Component claim = text("claim-button", "&a&lCLICK TO CLAIM", Map.of())
                        .clickEvent(ClickEvent.runCommand("/mail"))
                        .hoverEvent(Component.text("Claim your mail with /mail"));
                session.player.sendMessage(notice.append(Component.space()).append(claim));
            }
        }
        session.notified.retainAll(visible);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (stopping) { unavailable(sender); return true; }
        if (command.getName().equalsIgnoreCase("mail")) {
            if (!(sender instanceof Player player)) { say(sender, "players-only", "&cPlayers can claim mail with /mail.", Map.of()); return true; }
            if (args.length != 0) { sender.sendMessage("/mail"); return true; }
            Session session = sessions.get(player.getUniqueId());
            if (session == null || session.player != player || !ready(session)) {
                say(sender, "not-ready", "&7Please wait until you are alive and your inventory has loaded, then use /mail again.", Map.of()); return true;
            }
            if (!claiming.add(player.getUniqueId())) { say(sender, "busy", "&7Your mail is already being claimed.", Map.of()); return true; }
            session.warnedMissing = false;
            MailRecord cursor = session.cursor;
            submit(() -> {
                List<MailRecord> pending = cursor == null ? store.pending(session.id, BATCH)
                        : store.pendingAfter(session.id, cursor, BATCH);
                // A new pass revisits gifts skipped on another backend without claiming them twice.
                return pending.isEmpty() && cursor != null ? store.pending(session.id, BATCH) : pending;
            }, pending -> {
                if (pending.isEmpty()) { session.cursor = null; claiming.remove(player.getUniqueId()); say(sender, "empty", "&7You have no unclaimed mail.", Map.of()); }
                else deliver(session, pending, 0, 0);
            }, error -> { claiming.remove(player.getUniqueId()); unavailable(sender); });
            return true;
        }
        if (!sender.hasPermission(PERMISSION)) { say(sender, "no-permission", "&cYou do not have permission to send mail.", Map.of()); return true; }
        if (args.length < 2) return usage(sender);
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("all")) {
            String commandText = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            send(sender, Optional.empty(), commandText); return true;
        }
        if (!Set.of("player", "status", "ack").contains(action)
                || (action.equals("player") && args.length < 3)
                || (action.equals("status") && args.length != 2)
                || (action.equals("ack") && args.length != 3)) return usage(sender);
        if (action.equals("ack") && !(sender instanceof ConsoleCommandSender) && !(sender instanceof RemoteConsoleCommandSender)) {
            sender.sendMessage("Mail acknowledgement is console-only. Check the command's effects before acknowledging."); return true;
        }
        UUID acknowledgement;
        try { acknowledgement = action.equals("ack") ? UUID.fromString(args[2]) : null; }
        catch (IllegalArgumentException error) { return usage(sender); }
        UUID cached = localPlayer(args[1]);
        UUID explicitId;
        try { explicitId = UUID.fromString(args[1]); } catch (IllegalArgumentException ignored) { explicitId = null; }
        UUID uuidInput = explicitId;
        String target = args[1];
        // Snapshot staff identity and command input before leaving the server thread.
        String identity = identity(sender);
        String commandText = action.equals("player") ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "";
        if (action.equals("player") && !validCommand(commandText)) { invalidCommand(sender); return true; }
        submit(() -> {
            if (uuidInput != null) return Optional.of(uuidInput);
            Optional<UUID> network = store.findPlayer(target);
            return network.isPresent() ? network : Optional.ofNullable(cached);
        }, found -> {
            if (found.isEmpty()) { say(sender, "unknown-player", "&cPlayer not found. Use a player who has joined a participating backend, or their UUID.", Map.of()); return; }
            UUID id = found.get();
            switch (action) {
                case "player" -> saveMail(sender, Optional.of(id), identity, commandText);
                case "status" -> submit(() -> store.review(id, 100), records -> {
                    sender.sendMessage("Mail requiring review: " + records.size() + " (up to 100 shown)");
                    for (MailRecord record : records) sender.sendMessage("Mail " + record.id() + ": " + record.state()
                            + ", server " + record.serverId() + ", sender " + record.sender() + ", command: " + record.command());
                }, error -> unavailable(sender));
                case "ack" -> submit(() -> { store.acknowledge(id, acknowledgement); return true; },
                        ignored -> sender.sendMessage("Mail acknowledged. No command was repeated."), error -> unavailable(sender));
            }
        }, error -> unavailable(sender));
        return true;
    }
    private UUID localPlayer(String name) {
        try { return UUID.fromString(name); } catch (IllegalArgumentException ignored) { }
        for (Session session : sessions.values()) if (session.player.getName().equalsIgnoreCase(name)) return session.id;
        OfflinePlayer cached = plugin.getServer().getOfflinePlayerIfCached(name);
        return cached == null ? null : cached.getUniqueId();
    }
    private void send(CommandSender sender, Optional<UUID> target, String command) {
        if (!validCommand(command)) { invalidCommand(sender); return; }
        saveMail(sender, target, identity(sender), command);
    }
    static boolean validCommand(String command) {
        return !command.isBlank() && command.length() <= 2048 && !command.startsWith("/")
                && command.chars().noneMatch(c -> Character.isISOControl(c))
                && command.split(" ", 2)[0].matches("[A-Za-z0-9_:.-]+");
    }
    private void invalidCommand(CommandSender sender) {
        say(sender, "invalid-command", "&cUse one console command without a leading slash (maximum 2048 characters). Tokens: {player}, {uuid}.", Map.of());
    }
    private void saveMail(CommandSender sender, Optional<UUID> target, String identity, String command) {
        submit(() -> store.send(target, identity, command), count -> {
            say(sender, "sent", "&aMail saved for {count} player(s).", Map.of("count", Integer.toString(count)));
            poll();
        }, error -> unavailable(sender));
    }
    private String identity(CommandSender sender) {
        if (!(sender instanceof Player player)) return "&d&lPLANETRY";
        String rank = MailRanks.rank(plugin, player);
        return rank + " &f" + player.getName();
    }
    private void deliver(Session session, List<MailRecord> pending, int index, int delivered) {
        UUID id = session.id;
        if (index > 0) session.cursor = pending.get(index - 1);
        if (!ready(session) || index >= pending.size()) {
            if (index >= pending.size() && pending.size() < BATCH) session.cursor = null;
            claiming.remove(id);
            if (current(session)) {
                say(session.player, "claimed", "&aClaimed {count} mail item(s). Use /mail again if more remain.", Map.of("count", Integer.toString(delivered)));
                if (!ready(session)) say(session.player, "not-ready", "&7Please wait until you are alive and your inventory has loaded, then use /mail again.", Map.of());
            }
            return;
        }
        MailRecord mail = pending.get(index);
        String name = session.player.getName();
        String command = mail.command().replace("{player}", name).replace("{uuid}", id.toString());
        if (!name.matches("[A-Za-z0-9_.-]{1,32}") || !validCommand(command)
                || plugin.getServer().getCommandMap().getCommand(command.split(" ", 2)[0]) == null) {
            if (!session.warnedMissing) {
                session.warnedMissing = true;
                say(session.player, "command-unavailable", "&cA mail command is unavailable on this backend. Your mail is still saved; contact staff or claim on the correct server.", Map.of());
            }
            deliver(session, pending, index + 1, delivered);
            return;
        }
        submit(() -> store.claim(id, mail.id(), serverId), claimed -> {
            if (claimed.isEmpty()) { deliver(session, pending, index + 1, delivered); return; }
            MailRecord record = claimed.get();
            boolean success = false;
            try {
                if (!ready(session)) throw new IllegalStateException("Session changed after durable claim");
                // CommandMap.dispatch reports only that a command was found, discarding execute's
                // false result. Preserve that result so rejected plugin commands stay reviewable.
                String[] parts = command.split(" ");
                String label = parts[0].toLowerCase(Locale.ROOT);
                Command target = plugin.getServer().getCommandMap().getCommand(label);
                success = target != null && target.execute(plugin.getServer().getConsoleSender(), label,
                        Arrays.copyOfRange(parts, 1, parts.length));
            } catch (Exception | LinkageError error) { /* The persisted claim prevents unsafe retry. */ }
            boolean completed = success;
            if (!success) {
                plugin.getLogger().warning("Mail requires operator review: player " + id + ", mail " + record.id()
                        + ". The command will not be retried automatically.");
                if (current(session)) say(session.player, "review", "&cA mail item needs staff review. It will not be repeated automatically.", Map.of());
            }
            submit(() -> { store.complete(id, record.id(), record.claimId(), completed); return true; },
                    ignored -> deliver(session, pending, index + 1, delivered + (completed ? 1 : 0)),
                    error -> { claiming.remove(id); if (current(session)) unavailable(session.player); });
        }, error -> { claiming.remove(id); if (current(session)) unavailable(session.player); });
    }

    private boolean usage(CommandSender sender) {
        sender.sendMessage("/sendmail player <username|uuid> <command>, /sendmail all <command>");
        sender.sendMessage("/sendmail status <username|uuid>, console: /sendmail ack <username|uuid> <mail-uuid>");
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) return List.of();
        List<String> options = args.length == 1 ? List.of("player", "all", "status")
                : args.length == 2 && Set.of("player", "status").contains(args[0].toLowerCase(Locale.ROOT))
                ? sessions.values().stream().map(session -> session.player.getName()).toList() : List.of();
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
    private Component text(String key, String fallback, Map<String, String> values) {
        String format = plugin.getConfig().getString("mail.messages." + key, fallback);
        // Only replace configured tokens once, so sender metadata cannot introduce another token.
        StringBuilder result = new StringBuilder();
        var matcher = java.util.regex.Pattern.compile("\\{([a-z]+)}").matcher(format);
        int start = 0;
        while (matcher.find()) { result.append(format, start, matcher.start()).append(values.getOrDefault(matcher.group(1), matcher.group())); start = matcher.end(); }
        result.append(format, start, format.length());
        return TEXT.deserialize(result.toString());
    }
    private void say(CommandSender sender, String key, String fallback, Map<String, String> values) { sender.sendMessage(text(key, fallback, values)); }
    private void unavailable(CommandSender sender) {
        say(sender, "unavailable", "&cMail storage could not confirm this operation. Contact staff before repeating a send or claim.", Map.of());
    }
    @FunctionalInterface private interface Work<T> { T run() throws Exception; }
    private <T> void submit(Work<T> work, Consumer<T> success, Consumer<Exception> failure) {
        try {
            worker.execute(() -> {
                try { T result = work.run(); main(() -> success.accept(result)); }
                catch (Exception error) {
                    plugin.getLogger().warning("A mail storage operation was not confirmed (" + error.getClass().getSimpleName() + ").");
                    main(() -> failure.accept(error));
                }
            });
        } catch (RejectedExecutionException error) { failure.accept(error); }
    }
    private void main(Runnable action) {
        if (!stopping) try { plugin.getServer().getScheduler().runTask(plugin, () -> { if (!stopping) action.run(); }); }
        catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { }
    }
    @Override public void close() {
        stopping = true;
        if (timer != null) timer.cancel();
        Map<UUID, UUID> tokens = new HashMap<>();
        sessions.forEach((id, session) -> tokens.put(id, session.token));
        sessions.clear();
        // Queue cleanup after accepted writes. Expiring presence handles a full queue or crash.
        try { worker.execute(() -> tokens.forEach((id, token) -> {
            try { store.disconnect(id, token); } catch (Exception ignored) { }
        })); } catch (RejectedExecutionException ignored) { }
        worker.shutdown();
        try { if (!worker.awaitTermination(30, TimeUnit.SECONDS)) plugin.getLogger().warning("Mail storage is still finishing accepted operations."); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }
}
