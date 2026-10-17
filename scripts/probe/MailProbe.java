package dev.flint.planetrycore.probe;

import dev.flint.planetry.core.PlanetryCore;
import dev.flint.planetry.core.mail.MailState;
import dev.flint.planetry.core.mail.MailStore;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/** Controlled players in real Paper, independent of the XP probes and real-client acceptance. */
public final class MailProbe extends JavaPlugin {
    private static final String PERMISSION = "planetry.admin.sendmail";
    private static final Pattern CLAIMED = Pattern.compile("Claimed [0-9]+ mail item\\(s\\)\\..*");
    private static final Pattern REVIEW = Pattern.compile("Mail ([0-9a-f-]{36}): REVIEW.*");
    private final Map<UUID, String> names = new HashMap<>();
    private final List<Award> awards = new ArrayList<>();
    private final List<String> consoleMessages = new ArrayList<>();
    private PlanetryCore core;
    private Plugin inventory;
    private MailStore store;
    private CommandSender console;
    private Fake alice;
    private Fake bob;
    private Fake known;
    private Fake late;
    private Fake staff;
    private boolean hooks;
    private boolean finished;
    private boolean returnFalse;
    private boolean throwCommand;
    private boolean reconnectOnAward;
    private boolean unreadyOnAward;
    private Fake stale;
    private String stage = "startup";
    private static boolean rankAvailable = true;

    private record Award(String token, String name, UUID id) { }

    @Override public void onEnable() {
        getCommand("mailprobeaward").setExecutor(this);
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                Plugin loaded = Bukkit.getPluginManager().getPlugin("PlanetryCore");
                check(loaded instanceof PlanetryCore && loaded.isEnabled(), "Core did not enable");
                core = (PlanetryCore) loaded;
                inventory = Bukkit.getPluginManager().getPlugin("NetworkInventorySync");
                hooks = inventory != null;
                for (String name : new String[]{"Vault", "PlaceholderAPI", "NetworkInventorySync"}) {
                    Plugin hook = Bukkit.getPluginManager().getPlugin(name);
                    check(hooks ? hook != null && hook.isEnabled() : hook == null,
                            "Unexpected optional-plugin state: " + name);
                }
                if (hooks) Hooks.register();
                // Access only the real store, on an async task, to simulate another backend's send.
                // That send cannot trigger MailService.saveMail's immediate poll.
                // Reflecting Core's fields can resolve its optional PlanetryExpansion type.
                // The command executor exposes MailService without loading that PAPI class.
                Object service = core.getCommand("mail").getExecutor();
                store = (MailStore) field(service, "store");
                console = console();
                alice = new Fake("MailAlice", false);
                bob = new Fake("MailBob", false);
                known = new Fake("MailKnown", false);
                late = new Fake("MailLate", false);
                staff = new Fake("MailAdmin", true);
                check(core.getCommand("mail").getPermission() == null
                                || core.getCommand("mail").getPermission().isBlank(), "/mail requires a permission");
                check(PERMISSION.equals(core.getCommand("sendmail").getPermission()),
                        "/sendmail permission differs from the public contract");
                permissionAndSyntax();
            } catch (Throwable error) { fail(error); }
        });
    }

    private void permissionAndSyntax() {
        stage = "permission and malformed commands";
        for (Fake player : List.of(alice, staff)) {
            check(core.getCommand("mail").tabComplete(player.player, "mail", new String[]{""}).isEmpty(),
                    "/mail suggested staff arguments or player names");
            check(core.getCommand("mail").tabComplete(player.player, "mail", new String[]{"p"}).isEmpty(),
                    "/mail suggested arguments after a prefix");
        }
        check(core.getCommand("sendmail").tabComplete(staff.player, "sendmail", new String[]{""})
                .containsAll(List.of("player", "all", "status")), "/sendmail lost staff completions");
        execute("sendmail", alice.player, "player", alice.id.toString(), "mailprobeaward", "denied", "{player}", "{uuid}");
        check(!alice.messages.isEmpty(), "Permission denial produced no response");
        check(alice.permissionChecks.contains(PERMISSION), "Wrong administrative permission was checked");
        consoleMessages.clear();
        execute("mail", console);
        check(!consoleMessages.isEmpty(), "Console /mail produced no players-only response");
        String[][] malformed = {
                {}, {"player"}, {"all"}, {"status"},
                {"ack", alice.id.toString(), "invalid-uuid"},
                {"player", alice.id.toString(), ""},
                {"player", alice.id.toString(), "/mailprobeaward", "bad", "{player}", "{uuid}"},
                {"player", alice.id.toString(), "mailprobeaward", "bad\nop someone"},
                {"player", alice.id.toString(), "mailprobeaward", "x".repeat(2050)}
        };
        for (String[] args : malformed) {
            consoleMessages.clear();
            execute("sendmail", console, args);
            check(!consoleMessages.isEmpty() && consoleMessages.stream().noneMatch(line -> line.startsWith("Mail saved")),
                    "Malformed command was accepted: " + List.of(args));
        }
        consoleMessages.clear();
        execute("sendmail", console, "player", "NeverJoinedMail", "mailprobeaward", "unknown", "{player}", "{uuid}");
        waitFor(() -> !consoleMessages.isEmpty(), () -> {
            check(consoleMessages.stream().anyMatch(line -> line.contains("Player not found")), "Unknown name was accepted");
            send(console, consoleMessages, alice.id.toString(), "offline<red>{count}", this::offlineReminder);
        });
    }

    private void offlineReminder() {
        stage = "offline UUID delivery and login reminders";
        check(awards.isEmpty(), "Saving offline mail ran a command");
        join(alice);
        waitFor(() -> notice(alice, "PLANETRY"), () -> {
            assertNotice(alice, "PLANETRY");
            after(105, () -> {
                check(awards.isEmpty(), "Mail was automatically claimed after login or polling");
                Fake previous = alice;
                quit(previous);
                alice = new Fake(previous.id, previous.name, false);
                join(alice);
                waitFor(() -> notice(alice, "PLANETRY"), () -> {
                    assertNotice(alice, "PLANETRY");
                    check(awards.isEmpty(), "Reconnect automatically claimed saved mail");
                    alice.clear();
                    execute("mail", alice.player, "extra");
                    check(!alice.messages.isEmpty(), "Malformed /mail produced no usage");
                    claim(alice, 1, () -> {
                        checkToken("offline<red>{count}", 1);
                        empty(alice, () -> {
                            check(awards.size() == 1, "Repeated /mail duplicated an offline delivery");
                            registerKnownName();
                        });
                    });
                });
            });
        });
    }

    private void registerKnownName() {
        stage = "persisted network username after join and quit";
        join(known);
        // A mail-worker round trip is a barrier after its registration work.
        status(known, 0, () -> {
            quit(known);
            send(console, consoleMessages, known.name.toLowerCase(java.util.Locale.ROOT), "known-name", () -> {
                check(count("known-name") == 0, "Offline named mail ran immediately");
                known = new Fake(known.id, known.name, false);
                join(known);
                waitFor(() -> notice(known, "PLANETRY"), () -> claim(known, 1, () -> {
                    checkToken("known-name", 1);
                    quit(known);
                    rankNotification();
                }));
            });
        });
    }

    private void rankNotification() {
        stage = "player sender rank and name";
        alice.clear();
        String rank = hooks ? "ProbeRank" : "Staff";
        rankAvailable = true;
        send(staff.player, staff.messages, alice.name, "staff-rank", () ->
                waitFor(() -> notice(alice, staff.name), () -> {
                    assertNotice(alice, staff.name);
                    check(alice.messages.stream().anyMatch(line -> line.contains(rank + " " + staff.name)),
                            "Sender rank/name missing: " + alice.messages);
                    claim(alice, 1, () -> {
                        checkToken("staff-rank", 1);
                        rankAvailable = false;
                        alice.clear();
                        send(staff.player, staff.messages, alice.name, "staff-fallback", () ->
                                waitFor(() -> notice(alice, staff.name), () -> {
                                    assertNotice(alice, staff.name);
                                    check(alice.messages.stream().anyMatch(line -> line.contains("Staff") && line.contains(staff.name)),
                                            "Staff fallback/name missing: " + alice.messages);
                                    claim(alice, 1, this::periodicPoll);
                                }));
                    });
                }));
    }

    private void periodicPoll() {
        stage = "independent asynchronous 100-tick polling";
        alice.clear();
        UUID recipient = alice.id;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                int saved = store.send(Optional.of(recipient), "&d&lPLANETRY", command("remote-poll"));
                check(saved == 1, "External mail was not saved");
                Bukkit.getScheduler().runTask(this, () -> {
                    try {
                        waitFor(() -> notice(alice, "PLANETRY"), () -> {
                            assertNotice(alice, "PLANETRY");
                            check(count("remote-poll") == 0, "Poll automatically claimed mail");
                            claim(alice, 1, this::broadcast);
                        });
                    } catch (Throwable error) { fail(error); }
                });
            } catch (Throwable error) {
                Bukkit.getScheduler().runTask(this, () -> fail(error));
            }
        });
    }

    private void broadcast() {
        stage = "all snapshots registered online players";
        join(bob);
        status(bob, 0, () -> {
            alice.clear(); bob.clear(); late.clear();
            consoleMessages.clear();
            execute("sendmail", console, "all", "mailprobeaward", "broadcast", "{player}", "{uuid}");
            // This registration is enqueued after send-all's snapshot request.
            join(late);
            waitFor(() -> consoleMessages.stream().anyMatch(line -> line.startsWith("Mail saved")), () -> {
                check(consoleMessages.contains("Mail saved for 2 player(s)."),
                        "Broadcast did not snapshot exactly the joined online players: " + consoleMessages);
                check(consoleMessages.contains("Command: " + command("broadcast")), "Broadcast confirmation omitted the saved command");
                waitFor(() -> notice(alice, "PLANETRY") && notice(bob, "PLANETRY"), () -> {
                    check(count("broadcast") == 0, "Broadcast automatically executed");
                    claim(alice, 1, () -> claim(bob, 1, () -> empty(late, () -> {
                        checkToken("broadcast", 2);
                        check(awards.stream().filter(a -> a.token.equals("broadcast"))
                                        .map(Award::id).distinct().count() == 2,
                                "Broadcast delivered twice to one recipient");
                        check(awards.stream().noneMatch(a -> a.token.equals("broadcast")
                                        && (a.id.equals(late.id) || a.id.equals(known.id))),
                                "Broadcast included an offline or later-joining player");
                        quit(bob); quit(late);
                        readinessGate();
                    })));
                });
            });
        });
    }

    private void readinessGate() {
        stage = "living player and Inventory Sync readiness gate";
        alice.dead = true;
        readiness(false);
        send(console, consoleMessages, alice.id.toString(), "gated", () -> {
            int before = awards.size();
            alice.clear();
            execute("mail", alice.player);
            check(alice.messages.stream().anyMatch(line -> line.contains("/mail again")), "Dead player was not told to retry");
            after(3, () -> {
                check(awards.size() == before, "Dead player reserved or executed mail");
                alice.dead = false;
                if (hooks) {
                    alice.clear();
                    execute("mail", alice.player);
                    check(alice.messages.stream().anyMatch(line -> line.contains("/mail again")), "Unready inventory was not told to retry");
                }
                after(3, () -> {
                    check(awards.size() == before, "Unready inventory executed mail");
                    readiness(true);
                    after(105, () -> {
                        check(awards.size() == before, "Readiness transition automatically claimed mail");
                        claim(alice, 1, () -> {
                            checkToken("gated", 1);
                            gateBetweenItems();
                        });
                    });
                });
            });
        });
    }

    private void gateBetweenItems() {
        stage = "readiness is rechecked between sequential effects";
        send(console, consoleMessages, alice.id.toString(), "gate-first", () ->
                send(console, consoleMessages, alice.id.toString(), "gate-next", () -> {
                    unreadyOnAward = true;
                    claim(alice, 1, () -> {
                        checkToken("gate-first", 1);
                        checkToken("gate-next", 0);
                        alice.dead = false; readiness(true);
                        claim(alice, 1, () -> {
                            checkToken("gate-next", 1);
                            sessionFence();
                        });
                    });
                }));
    }

    private void sessionFence() {
        stage = "current-session fencing between sequential effects";
        send(console, consoleMessages, alice.id.toString(), "session-first", () ->
                send(console, consoleMessages, alice.id.toString(), "session-next", () -> {
                    reconnectOnAward = true;
                    execute("mail", alice.player);
                    waitFor(() -> count("session-first") == 1 && notice(alice, "PLANETRY"), () -> status(alice, 0, () -> {
                        checkToken("session-next", 0);
                        // Even a stale object still reporting online cannot claim the new session's mail.
                        stale.online = true;
                        execute("mail", stale.player);
                        check(stale.messages.stream().anyMatch(line -> line.contains("/mail again")), "Old session was allowed to claim");
                        stale.online = false;
                        claim(alice, 1, () -> {
                            checkToken("session-first", 1); checkToken("session-next", 1);
                            queueBatch(0);
                        });
                    }));
                }));
    }

    private void queueBatch(int index) {
        stage = "50-item batch limit and sequential command ordering";
        if (index < 51) {
            send(console, consoleMessages, alice.id.toString(), "batch-" + index, () -> queueBatch(index + 1));
            return;
        }
        int before = awards.size();
        claim(alice, 50, () -> {
            for (int i = 0; i < 50; i++) check(awards.get(before + i).token.equals("batch-" + i),
                    "Mail commands were not executed in pending order at item " + i);
            checkToken("batch-50", 0);
            claim(alice, 1, () -> {
                checkToken("batch-50", 1);
                empty(alice, this::missingCommand);
            });
        });
    }

    private void missingCommand() {
        stage = "a full unavailable page does not starve later usable mail";
        check(Bukkit.getCommandMap().getCommand("mailprobelate") == null, "Late test command already registered");
        queueMissingCommands(0);
    }

    private void queueMissingCommands(int index) {
        if (index < 50) {
            // Separate durable confirmations preserve creation order, including with file storage.
            save(console, consoleMessages, alice.id.toString(),
                    "mailprobelate missing-" + index + " {player} {uuid}", () -> queueMissingCommands(index + 1));
            return;
        }
        send(console, consoleMessages, alice.id.toString(), "after-missing", () -> claim(alice, 0, () -> {
            checkToken("after-missing", 0);
            // The next explicit request must advance beyond the skipped full page.
            claim(alice, 1, () -> {
                checkToken("after-missing", 1);
                for (int i = 0; i < 50; i++) checkToken("missing-" + i, 0);
                retainedMissingMail(this::registerLateCommand);
            });
        }));
    }

    private void retainedMissingMail(Runnable next) {
        UUID recipient = alice.id;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                var pending = store.pending(recipient, 51);
                check(pending.size() == 50, "Skipped mail was reserved or removed: pending=" + pending.size());
                check(pending.stream().allMatch(mail -> mail.state() == MailState.PENDING && mail.claimId() == null),
                        "Unavailable mail acquired a durable claim");
                for (int i = 0; i < 50; i++) {
                    String expected = "mailprobelate missing-" + i + " {player} {uuid}";
                    check(pending.stream().filter(mail -> mail.command().equals(expected)).count() == 1,
                            "Unavailable mail was not retained exactly once: " + expected);
                }
                Bukkit.getScheduler().runTask(this, () -> {
                    try { if (!finished) next.run(); } catch (Throwable error) { fail(error); }
                });
            } catch (Throwable error) {
                Bukkit.getScheduler().runTask(this, () -> fail(error));
            }
        });
    }

    private void registerLateCommand() {
        boolean registered = Bukkit.getCommandMap().register("mailprobe", new Command("mailprobelate") {
            @Override public boolean execute(CommandSender sender, String label, String[] args) {
                return MailProbe.this.onCommand(sender, this, label, args);
            }
        });
        check(registered, "Could not register late test command");
        int before = awards.size();
        // The partial final page must reset the cursor so all skipped mail is reachable again.
        claim(alice, 50, () -> {
            for (int i = 0; i < 50; i++) {
                checkToken("missing-" + i, 1);
                check(awards.get(before + i).token.equals("missing-" + i), "Skipped mail recovery changed pending order at " + i);
            }
            checkToken("after-missing", 1);
            empty(alice, () -> failedDelivery(false));
        });
    }

    private void failedDelivery(boolean exception) {
        String token = exception ? "exception" : "false";
        stage = "command " + token + ", durable review and console-only acknowledgement";
        send(console, consoleMessages, alice.id.toString(), token, () -> {
            returnFalse = !exception; throwCommand = exception;
            claim(alice, 1, () -> {
                returnFalse = false; throwCommand = false;
                checkToken(token, 1);
                status(alice, 1, () -> {
                    String id = consoleMessages.stream().map(REVIEW::matcher).filter(java.util.regex.Matcher::matches)
                            .map(matcher -> matcher.group(1)).findFirst().orElseThrow(() ->
                                    new IllegalStateException("Failed command was not durably REVIEW: " + consoleMessages));
                    staff.clear();
                    execute("sendmail", staff.player, "ack", alice.id.toString(), id);
                    check(staff.messages.stream().anyMatch(line -> line.contains("console-only")), "Player acknowledgement was not denied");
                    List<String> otherMessages = new ArrayList<>();
                    CommandSender other = sender(CommandSender.class, "MailProbeNonConsole", otherMessages);
                    execute("sendmail", other, "ack", alice.id.toString(), id);
                    check(otherMessages.stream().anyMatch(line -> line.contains("console-only")),
                            "Non-console sender acknowledgement was not denied");
                    status(alice, 1, () -> empty(alice, () -> after(105, () -> {
                        checkToken(token, 1);
                        consoleMessages.clear();
                        execute("sendmail", console, "ack", alice.id.toString(), id);
                        waitFor(() -> consoleMessages.stream().anyMatch(line -> line.startsWith("Mail acknowledged.")), () ->
                                status(alice, 0, () -> {
                                    checkToken(token, 1);
                                    if (exception) finish(); else failedDelivery(true);
                                }));
                    })));
                });
            });
        });
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        check(Bukkit.isPrimaryThread(), "Mail command executed off main thread");
        check(sender == Bukkit.getConsoleSender(), "Mail effect did not use the actual console sender");
        check(args.length == 3, "Unsubstituted or malformed award command: " + List.of(args));
        UUID id = UUID.fromString(args[2]);
        check(args[1].equals(names.get(id)), "Player/UUID placeholders do not match recipient: " + List.of(args));
        awards.add(new Award(args[0], args[1], id));
        if (unreadyOnAward) {
            unreadyOnAward = false;
            if (hooks) readiness(false); else alice.dead = true;
        }
        if (reconnectOnAward) {
            reconnectOnAward = false;
            stale = alice;
            quit(stale);
            alice = new Fake(stale.id, stale.name, false);
            join(alice);
        }
        if (throwCommand) throw new IllegalStateException("Intentional mail fixture exception after recorded effect");
        return !returnFalse;
    }

    private void finish() {
        checkToken("false", 1); checkToken("exception", 1);
        checkToken("denied", 0); checkToken("unknown", 0);
        check(core.isEnabled(), "Core disabled during mail acceptance");
        finished = true;
        getLogger().info("CORE_MAIL_PROBE_OK: real Paper; " + (hooks ? "actual PAPI and optional hooks" : "no optional hooks")
                + "; permission/syntax, offline UUID/name mail, join reminders, clickable sender attribution, periodic poll, no auto-claim,"
                + " console/main-thread effects, placeholders, broadcast snapshot, living/readiness/session fencing, sequential 50-item batches,"
                + " unavailable-page pagination and recovery, false/exception review, no automatic retries and console-only acknowledgement without repeated effects");
        Bukkit.shutdown();
    }

    private void send(CommandSender sender, List<String> messages, String target, String token, Runnable next) {
        save(sender, messages, target, command(token), next);
    }

    private void save(CommandSender sender, List<String> messages, String target, String command, Runnable next) {
        messages.clear();
        List<String> args = new ArrayList<>(List.of("player", target));
        args.addAll(List.of(command.split(" ")));
        execute("sendmail", sender, args.toArray(String[]::new));
        waitFor(() -> messages.stream().anyMatch(line -> line.startsWith("Mail saved")), () -> {
            check(messages.contains("Mail saved for 1 player(s)."), "Individual send was not saved once: " + messages);
            check(messages.contains("Command: " + command), "Confirmation omitted or reformatted the exact saved command: " + messages);
            next.run();
        });
    }

    private void claim(Fake target, int attempts, Runnable next) {
        int before = awards.size();
        target.clear();
        execute("mail", target.player);
        waitFor(() -> target.messages.stream().anyMatch(line -> CLAIMED.matcher(line).matches()), () -> {
            check(awards.size() == before + attempts,
                    "Claim attempted " + (awards.size() - before) + " effects, expected " + attempts + ": " + target.messages);
            next.run();
        });
    }

    private void empty(Fake target, Runnable next) {
        int before = awards.size();
        target.clear();
        execute("mail", target.player);
        // A second overlapping request must also leave effects unchanged.
        execute("mail", target.player);
        waitFor(() -> target.messages.stream().anyMatch(line -> line.contains("no unclaimed mail")), () -> {
            check(awards.size() == before, "Repeated /mail repeated an external effect");
            next.run();
        });
    }

    private void status(Fake target, int expected, Runnable next) {
        consoleMessages.clear();
        execute("sendmail", console, "status", target.id.toString());
        waitFor(() -> consoleMessages.stream().anyMatch(line -> line.startsWith("Mail requiring review:")), () -> {
            check(consoleMessages.contains("Mail requiring review: " + expected + " (up to 100 shown)"),
                    "Unexpected review status: " + consoleMessages);
            next.run();
        });
    }

    private void execute(String name, CommandSender sender, String... args) {
        check(Bukkit.isPrimaryThread(), "Probe command invoked off main thread");
        PluginCommand command = Bukkit.getPluginCommand(name);
        check(command != null && command.getPlugin() == core, "Missing Core command /" + name);
        command.execute(sender, name, args);
    }
    private void join(Fake player) { player.online = true; core.join(new PlayerJoinEvent(player.player, Component.empty())); }
    private void quit(Fake player) {
        player.online = false;
        core.quit(new PlayerQuitEvent(player.player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
    }
    private void readiness(boolean ready) {
        if (!hooks) return;
        try { inventory.getClass().getField("ready").setBoolean(inventory, ready); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private static Object field(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private static String command(String token) { return "mailprobeaward " + token + " {player} {uuid}"; }
    private long count(String token) { return awards.stream().filter(award -> award.token.equals(token)).count(); }
    private void checkToken(String token, int expected) { check(count(token) == expected, token + " effects: " + count(token) + ", expected " + expected); }
    private static boolean notice(Fake target, String sender) {
        return target.messages.stream().anyMatch(line -> line.contains(sender) && line.contains("CLICK TO CLAIM"));
    }
    private static void assertNotice(Fake target, String sender) {
        check(notice(target, sender), "Mail sender/claim text missing: " + target.messages);
        check(target.components.stream().anyMatch(component -> PlainTextComponentSerializer.plainText().serialize(component).contains(sender)
                && claimClick(component)), "Notice lacks Adventure RUN_COMMAND /mail: " + target.messages);
    }
    private static boolean claimClick(Component component) {
        ClickEvent event = component.clickEvent();
        if (event != null && event.action() == ClickEvent.Action.RUN_COMMAND && event.value().equals("/mail")) return true;
        return component.children().stream().anyMatch(MailProbe::claimClick);
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
                    else if (++ticks > 400) { cancel(); fail(new IllegalStateException("Timed out: " + stage
                            + "; console=" + consoleMessages + "; player=" + (alice == null ? "[]" : alice.messages))); }
                } catch (Throwable error) { cancel(); fail(error); }
            }
        }.runTaskTimer(this, 1, 1);
    }
    private void fail(Throwable error) {
        if (finished) return;
        finished = true;
        getLogger().severe("CORE_MAIL_PROBE_FAILED: " + stage + ": " + error);
        error.printStackTrace();
        Bukkit.shutdown();
    }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }

    private CommandSender console() {
        return sender(ConsoleCommandSender.class, "MailProbeConsole", consoleMessages);
    }

    private static CommandSender sender(Class<? extends CommandSender> type, String name, List<String> messages) {
        return (CommandSender) Proxy.newProxyInstance(type.getClassLoader(),
                new Class<?>[]{type}, (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getServer" -> Bukkit.getServer();
                    case "hasPermission", "isPermissionSet", "isOp" -> true;
                    case "sendMessage" -> { append(messages, null, args); yield null; }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> name;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static void append(List<String> messages, List<Component> components, Object[] args) {
        check(Bukkit.isPrimaryThread(), "Player/console message sent off main thread");
        if (args == null) return;
        for (Object arg : args) {
            if (arg instanceof String[] strings) messages.addAll(List.of(strings));
            else if (arg instanceof Component component) {
                messages.add(PlainTextComponentSerializer.plainText().serialize(component));
                if (components != null) components.add(component);
            } else if (arg instanceof String string) messages.add(string);
        }
    }
    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == char.class) return (char) 0;
        return null;
    }

    private final class Fake {
        final UUID id;
        final String name;
        final List<String> messages = new ArrayList<>();
        final List<Component> components = new ArrayList<>();
        final List<String> permissionChecks = new ArrayList<>();
        final Player player;
        boolean online = true;
        boolean dead;

        Fake(String name, boolean admin) { this(UUID.randomUUID(), name, admin); }
        Fake(UUID id, String name, boolean admin) {
            this.id = id; this.name = name;
            names.put(id, name);
            player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> id;
                        case "getName", "getDisplayName" -> name;
                        case "displayName", "name" -> Component.text(name);
                        case "getServer" -> Bukkit.getServer();
                        case "isOnline" -> online;
                        case "isDead" -> dead;
                        case "isValid", "hasPlayedBefore" -> true;
                        case "hasPermission", "isPermissionSet" -> {
                            String permission = args[0] instanceof String text ? text : ((org.bukkit.permissions.Permission) args[0]).getName();
                            permissionChecks.add(permission);
                            yield admin && permission.equals(PERMISSION);
                        }
                        case "isOp" -> false;
                        case "getLevel" -> 17;
                        case "getExp" -> .5f;
                        case "getTotalExperience" -> 321;
                        case "setLevel", "setExp", "setTotalExperience", "giveExp", "giveExpLevels", "setExperienceLevelAndProgress" ->
                                throw new IllegalStateException("Mail changed vanilla XP via " + method.getName());
                        case "sendExperienceChange" -> { check(Bukkit.isPrimaryThread(), "XP display sent off main thread"); yield null; }
                        case "sendMessage" -> { append(messages, components, args); yield null; }
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "MailProbe[" + name + "," + id + "]";
                        default -> defaultValue(method.getReturnType());
                    });
        }
        void clear() { messages.clear(); components.clear(); }
    }

    /** The JVM never loads this helper in --without-hooks mode. */
    private static final class Hooks {
        static void register() {
            boolean registered = new me.clip.placeholderapi.expansion.PlaceholderExpansion() {
                @Override public String getIdentifier() { return "mailprobe"; }
                @Override public String getAuthor() { return "PlanetryCore runtime acceptance"; }
                @Override public String getVersion() { return "1"; }
                @Override public boolean persist() { return true; }
                @Override public String onRequest(org.bukkit.OfflinePlayer player, String params) {
                    check(Bukkit.isPrimaryThread(), "Rank placeholder resolved off main thread");
                    return params.equals("rank") && rankAvailable ? "&6ProbeRank  &r" : "";
                }
            }.register();
            check(registered, "Test rank expansion did not register with actual PlaceholderAPI");
        }
    }
}
