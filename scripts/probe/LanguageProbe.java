package dev.flint.planetrycore.probe;

import dev.flint.planetry.core.Language;
import dev.flint.planetry.core.PlanetryCore;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/** Language/reload acceptance in real Paper with controlled players, not network clients. */
public final class LanguageProbe extends JavaPlugin {
    private static final String RELOAD = "planetry.admin.reload";
    private static final String PAPI = "%langprobe_name%";
    private static final String GLYPH = "<glyph:probe>";
    private static final String MARKUP = "<click:run_command:'/op languageprobe'>PAPI</click><glyph:probe>{player}%langprobe_name%";
    private static final String RAW = "%langprobe_name% <glyph:probe> <red>{player}";
    private final List<String> consoleMessages = new ArrayList<>();
    private final List<Component> consoleComponents = new ArrayList<>();
    private PlanetryCore core;
    private PluginCommand reload;
    private CommandSender console;
    private Fake alice;
    private Fake staff;
    private Path configFile;
    private Path languageDirectory;
    private String initialConfig;
    private Object mailExecutor;
    private boolean hooks;
    private boolean finished;
    private String stage = "startup";

    @Override public void onEnable() {
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                Plugin loaded = Bukkit.getPluginManager().getPlugin("PlanetryCore");
                check(loaded instanceof PlanetryCore && loaded.isEnabled(), "Core did not enable");
                core = (PlanetryCore) loaded;
                hooks = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
                for (String name : List.of("PlaceholderAPI", "Vault", "NetworkInventorySync", "Nexo")) {
                    Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
                    check(hooks ? plugin != null && plugin.isEnabled() : plugin == null,
                            "Unexpected optional-plugin state: " + name);
                }
                if (hooks) Hooks.register();
                alice = new Fake("LanguageAlice", false);
                staff = new Fake("LanguageStaff", true);
                console = console();
                reload = core.getCommand("planetrycore");
                check(reload != null, "Missing admin reload command");
                var permission = Bukkit.getPluginManager().getPermission(RELOAD);
                check(permission != null && permission.getDefault() == PermissionDefault.OP,
                        "Reload permission does not default to operators");
                configFile = core.getDataFolder().toPath().resolve("config.yml");
                languageDirectory = core.getDataFolder().toPath().resolve("lang");
                initialConfig = core.getConfig().saveToString();
                mailExecutor = core.getCommand("mail").getExecutor();
                permissionAndReload();
                core.join(new PlayerJoinEvent(alice.player, Component.empty()));
                waitFor(() -> core.cachedProgress(alice.id) != null, this::coreMessages);
            } catch (Throwable error) { fail(error); }
        });
    }

    private void permissionAndReload() throws Exception {
        stage = "permissions, syntax, completions and language-only reload";
        writeLanguage("en", "EN-A");
        writeSelection("en");
        Language initial = core.language();
        execute("planetrycore", console, "reload");
        check(core.language() != initial && core.language().name().equals("en"), "Reload did not replace the language snapshot");
        check(consoleMessages.stream().anyMatch(line -> line.equals("RELOADED-EN-A en")), "Reload success did not use the new language: " + consoleMessages);
        unchangedRuntime();
        check(plain(core.language().text(alice.player, "probe.marker", Map.of())).equals("EN-A"), "Edited text did not take effect");
        assertDenials("EN-A");
        Language current = core.language();
        writeLanguage("en", "EN-B");
        execute("planetrycore", alice.player, "reload");
        check(core.language() == current && alice.messages.contains("RELOAD-DENIED-EN-A " + expansion(alice.name) + " " + glyph()), "Unauthorized reload changed messages or gave the wrong denial");
        check(alice.permissions.contains(RELOAD), "Reload checked the wrong permission");
        for (String[] args : new String[][]{{}, {"unknown"}, {"reload", "extra"}}) {
            consoleMessages.clear();
            execute("planetrycore", console, args);
            check(core.language() == current && consoleMessages.contains("RELOAD-USAGE-EN-A"),
                    "Malformed reload was accepted: " + List.of(args) + ": " + consoleMessages);
        }
        check(reload.tabComplete(alice.player, "planetrycore", new String[]{""}).isEmpty(), "Unauthorized reload completion leaked");
        check(reload.tabComplete(staff.player, "planetrycore", new String[]{""}).equals(List.of("reload")), "Reload completion missing or unrelated entries offered");
        check(reload.tabComplete(staff.player, "planetrycore", new String[]{"Re"}).equals(List.of("reload")), "Reload completion is not case insensitive");
        check(reload.tabComplete(staff.player, "planetrycore", new String[]{"wrong"}).isEmpty(), "Unrelated completion offered");
        check(reload.tabComplete(staff.player, "planetrycore", new String[]{"reload", ""}).isEmpty(), "Extra reload arguments suggested");
        staff.clear();
        execute("planetrycore", staff.player, "reload");
        check(core.language() != current && staff.messages.contains("RELOADED-EN-B en"), "Authorized player reload did not load edited text");
        assertDenials("EN-B");
        unchangedRuntime();
        directFormatting();
    }

    private void coreMessages() {
        stage = "recipient-aware Core messages and console preservation";
        alice.clear();
        execute("level", alice.player);
        String expected = "LEVEL-EN-B LanguageAlice|0|0|" + expansion(alice.name) + "|" + glyph() + "|" + (hooks ? MARKUP : "%langprobe_markup%");
        check(alice.messages.contains(expected), "Core /level did not use recipient-aware language: " + alice.messages);
        check(alice.components.stream().noneMatch(component -> contains(component,
                node -> node.clickEvent() != null)), "PAPI output created an injected click event");
        if (hooks) {
            check(alice.components.stream().anyMatch(component -> contains(component,
                    node -> Key.key("nexo:probe").equals(node.font()))), "Native glyph font disappeared in Core output");
            check(alice.components.stream().anyMatch(component -> contains(component,
                    node -> NamedTextColor.GOLD.equals(node.color()))), "Legacy colors in PAPI output were not preserved");
        }
        consoleMessages.clear();
        execute("mail", console);
        check(consoleMessages.contains("CONSOLE-EN-B " + PAPI + " " + glyph()), "Console language incorrectly used a player for PAPI: " + consoleMessages);
        sendMail();
    }

    private void directFormatting() {
        Component value = core.language().text(alice.player, "probe.value", Map.of("value", RAW));
        check(plain(value).equals("VALUE " + RAW + " " + expansion(alice.name) + " " + glyph()),
                "A runtime value became a placeholder or glyph: " + plain(value));
        Component componentValue = core.language().text(alice.player, "probe.value", Map.of(),
                Map.of("value", Component.text(RAW).color(NamedTextColor.RED)));
        check(plain(componentValue).equals(plain(value)), "A supplied component was expanded or reformatted");
        check(contains(componentValue, node -> NamedTextColor.RED.equals(node.color())), "Supplied component formatting was lost");
        Component contextFree = core.language().text("probe.value", Map.of("value", RAW));
        check(plain(contextFree).equals("VALUE " + RAW + " " + PAPI + " " + glyph()),
                "Context-free language expanded a player placeholder");
        if (hooks) {
            Component shift = core.language().text(alice.player, "probe.shift", Map.of());
            check(plain(shift).equals("SHIFT \uE748") && contains(shift, node -> Key.key("nexo:shift").equals(node.font())),
                    "Native Nexo shift component was not preserved");
        } else check(plain(core.language().text(alice.player, "probe.shift", Map.of())).equals("SHIFT <shift:3>"),
                "Absent Nexo changed a shift tag");
    }

    private void sendMail() {
        stage = "mail notice, claim hover, staff feedback and literal stored commands";
        alice.clear(); staff.clear();
        String command = "say " + RAW;
        List<String> args = new ArrayList<>(List.of("player", alice.id.toString()));
        args.addAll(List.of(command.split(" ")));
        execute("sendmail", staff.player, args.toArray(String[]::new));
        waitFor(() -> staff.messages.stream().anyMatch(line -> line.startsWith("SENT-EN-B"))
                && alice.messages.stream().anyMatch(line -> line.startsWith("MAIL-EN-B")), () -> {
            check(staff.messages.contains("SENT-EN-B 1 " + expansion(staff.name) + " " + glyph()), "Mail staff feedback used the wrong recipient: " + staff.messages);
            check(staff.messages.contains("COMMAND " + command + " " + expansion(staff.name) + " " + glyph()),
                    "Stored command was expanded or parsed as language markup: " + staff.messages);
            check(alice.messages.stream().anyMatch(line -> line.startsWith("MAIL-EN-B " + expansion(alice.name) + " " + glyph())
                    && line.contains("BUTTON " + expansion(alice.name) + " " + glyph())),
                    "Mail notice/button used sender placeholders instead of recipient: " + alice.messages);
            check(alice.components.stream().anyMatch(component -> contains(component, node -> {
                ClickEvent click = node.clickEvent();
                HoverEvent<?> hover = node.hoverEvent();
                return click != null && click.action() == ClickEvent.Action.RUN_COMMAND && click.value().equals("/mail")
                        && hover != null && hover.action() == HoverEvent.Action.SHOW_TEXT && hover.value() instanceof Component text
                        && plain(text).equals("HOVER " + expansion(alice.name) + " " + glyph());
            })), "Mail claim click/recipient-aware hover was lost");
            try { localeAndFailures(); } catch (Throwable error) { fail(error); }
        });
    }

    private void localeAndFailures() throws Exception {
        stage = "locale switch and failed-reload snapshot retention";
        writeLanguage("de", "DE-C");
        writeSelection("de");
        consoleMessages.clear();
        execute("planetrycore", console, "reload");
        check(core.language().name().equals("de") && consoleMessages.contains("RELOADED-DE-C de"), "Reload did not read the new config language selection");
        assertDenials("DE-C");
        unchangedRuntime();
        Language good = core.language();
        Files.writeString(languageDirectory.resolve("de.yml"), "messages: [\nbroken YAML\n", StandardCharsets.UTF_8);
        rejectedReload(good);
        writeLanguage("de", "DE-C");
        writeSelection("../escape");
        rejectedReload(good);
        writeSelection("missing_language_probe");
        rejectedReload(good);
        Files.writeString(configFile, "language: [\n", StandardCharsets.UTF_8);
        rejectedReload(good);
        writeSelection("en");
        Files.delete(languageDirectory.resolve("en.yml"));
        rejectedReload(good);
        check(!Files.exists(languageDirectory.resolve("en.yml")), "Reload recreated a removed language file");
        // Startup-only import must never overwrite a running translation on reload.
        Files.deleteIfExists(languageDirectory.resolve(".legacy-config-imported"));
        writeLanguage("en", "EN-D");
        writeSelection("en");
        execute("planetrycore", console, "reload");
        check(core.language().name().equals("en") && plain(core.language().text("probe.marker", Map.of())).equals("EN-D"), "Reload did not recover after correcting the files");
        check(!Files.exists(languageDirectory.resolve(".legacy-config-imported")), "Reload reran legacy import");
        assertDenials("EN-D");
        unchangedRuntime();
        // An actual durable write proves the active storage still works after invalid disk settings.
        consoleMessages.clear();
        execute("gamexp", console, "give", alice.id.toString(), "23");
        waitFor(() -> core.cachedProgress(alice.id) != null && core.cachedProgress(alice.id).experience() == 23, this::disabledHooks);
    }

    private void rejectedReload(Language good) {
        consoleMessages.clear();
        execute("planetrycore", console, "reload");
        check(core.language() == good && consoleMessages.contains("RELOAD-FAILED-DE-C"), "Invalid reload discarded the working language or reported success: " + consoleMessages);
        check(plain(core.language().text("probe.marker", Map.of())).equals("DE-C"), "Invalid reload changed working messages");
        assertDenials("DE-C");
        unchangedRuntime();
    }

    private void disabledHooks() {
        stage = "disabled optional hooks";
        if (hooks) {
            try {
                Plugin nexo = Bukkit.getPluginManager().getPlugin("Nexo");
                int playerUses = nexo.getClass().getField("glyphPlayerUses").getInt(nexo);
                int globalUses = nexo.getClass().getField("glyphGlobalUses").getInt(nexo);
                check(playerUses > 0 && globalUses > 0, "Native Nexo player/global resolver paths were not exercised");
                Bukkit.getPluginManager().disablePlugin(Bukkit.getPluginManager().getPlugin("PlaceholderAPI"));
                Bukkit.getPluginManager().disablePlugin(nexo);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        Component literal = core.language().text(alice.player, "probe.value", Map.of("value", RAW));
        check(plain(literal).equals("VALUE " + RAW + " " + PAPI + " " + GLYPH), "Disabled/absent hooks expanded tokens or tags: " + plain(literal));
        check(core.isEnabled(), "Core disabled during language acceptance");
        finished = true;
        getLogger().info("CORE_LANGUAGE_PROBE_OK: real Paper; " + (hooks ? "actual PlaceholderAPI 2.12.3 and controlled native Nexo tag fixture" : "without optional plugins")
                + "; reload permission/arguments/tab, live edits and locale selection, unchanged storage/settings, invalid YAML/locale/missing-file retention,"
                + " recipient-aware Core/mail/button/hover, native glyph fonts, literal runtime/PAPI markup, legacy PAPI colors and disabled hooks");
        Bukkit.shutdown();
    }

    private void writeLanguage(String locale, String marker) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("probe.marker", marker);
        yaml.set("probe.value", "VALUE {value} " + PAPI + " " + GLYPH);
        yaml.set("probe.shift", "SHIFT <shift:3>");
        yaml.set("messages.level", "<aqua>LEVEL-" + marker + " {player}|{level}|{gamexp}|" + PAPI + "|" + GLYPH + "|%langprobe_markup%");
        yaml.set("messages.hint", "HINT-" + marker);
        yaml.set("messages.no-permission", "XP-DENIED-" + marker + " " + PAPI + " " + GLYPH);
        yaml.set("messages.reload-success", "RELOADED-" + marker + " {language}");
        yaml.set("messages.reload-failed", "RELOAD-FAILED-" + marker);
        yaml.set("messages.reload-usage", "RELOAD-USAGE-" + marker);
        yaml.set("messages.reload-no-permission", "RELOAD-DENIED-" + marker + " " + PAPI + " " + GLYPH);
        yaml.set("mail.no-permission", "MAIL-DENIED-" + marker + " " + PAPI + " " + GLYPH);
        yaml.set("mail.players-only", "CONSOLE-" + marker + " " + PAPI + " " + GLYPH);
        yaml.set("mail.notice", "MAIL-" + marker + " " + PAPI + " " + GLYPH + " {sender}");
        yaml.set("mail.claim-button", "BUTTON " + PAPI + " " + GLYPH);
        yaml.set("mail.claim-hover", "HOVER " + PAPI + " " + GLYPH);
        yaml.set("mail.sent", "SENT-" + marker + " {count} " + PAPI + " " + GLYPH);
        yaml.set("mail.sent-command", "COMMAND {command} " + PAPI + " " + GLYPH);
        yaml.save(languageDirectory.resolve(locale + ".yml").toFile());
    }

    private void writeSelection(String locale) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(initialConfig);
        yaml.set("language", locale);
        yaml.set("storage.type", "language-probe-invalid-storage");
        yaml.set("rewards.money", -1);
        yaml.set("display.xp-bar", false);
        yaml.set("hotbar.enabled", true);
        yaml.set("messages.hint", "LEGACY-IMPORT-MUST-NOT-RUN");
        yaml.save(configFile.toFile());
    }

    private void unchangedRuntime() {
        check(core.getConfig().saveToString().equals(initialConfig), "Language-only reload replaced active configuration");
        check(core.getCommand("mail").getExecutor() == mailExecutor, "Language-only reload replaced the mail service");
        check(core.isEnabled(), "Invalid non-language disk settings disabled Core");
    }

    private void assertDenials(String marker) {
        alice.clear();
        execute("gamexp", alice.player, "give", alice.id.toString(), "1");
        execute("sendmail", alice.player, "player", alice.id.toString(), "say", "denied");
        execute("planetrycore", alice.player, "reload");
        for (String prefix : List.of("XP-DENIED-", "MAIL-DENIED-", "RELOAD-DENIED-"))
            check(alice.messages.contains(prefix + marker + " " + expansion(alice.name) + " " + glyph()),
                    "Permission denial stayed stale or lost recipient formatting: " + alice.messages);
    }

    private String expansion(String name) { return hooks ? name : PAPI; }
    private String glyph() { return hooks ? "\uE741" : GLYPH; }
    private static String plain(Component component) { return PlainTextComponentSerializer.plainText().serialize(component); }
    private static boolean contains(Component component, Predicate<Component> test) {
        return test.test(component) || component.children().stream().anyMatch(child -> contains(child, test));
    }
    private void execute(String name, CommandSender sender, String... args) {
        check(Bukkit.isPrimaryThread(), "Probe command invoked off main thread");
        PluginCommand command = core.getCommand(name);
        check(command != null && command.getPlugin() == core, "Missing Core command /" + name);
        command.execute(sender, name, args);
    }
    private void waitFor(BooleanSupplier test, Runnable next) {
        new BukkitRunnable() {
            int ticks;
            @Override public void run() {
                try {
                    if (finished) { cancel(); return; }
                    if (test.getAsBoolean()) { cancel(); next.run(); }
                    else if (++ticks > 400) { cancel(); fail(new IllegalStateException("Timed out: " + stage + "; console=" + consoleMessages
                            + "; player=" + alice.messages + "; staff=" + staff.messages)); }
                } catch (Throwable error) { cancel(); fail(error); }
            }
        }.runTaskTimer(this, 1, 1);
    }
    private void fail(Throwable error) {
        if (finished) return;
        finished = true;
        getLogger().severe("CORE_LANGUAGE_PROBE_FAILED: " + stage + ": " + error);
        error.printStackTrace();
        Bukkit.shutdown();
    }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }

    private CommandSender console() {
        return (CommandSender) Proxy.newProxyInstance(ConsoleCommandSender.class.getClassLoader(), new Class<?>[]{ConsoleCommandSender.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "LanguageConsole";
                    case "getServer" -> Bukkit.getServer();
                    case "hasPermission", "isPermissionSet", "isOp" -> true;
                    case "sendMessage" -> { append(consoleMessages, consoleComponents, args); yield null; }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "LanguageConsole";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private final class Fake {
        final UUID id = UUID.randomUUID();
        final String name;
        final List<String> messages = new ArrayList<>();
        final List<Component> components = new ArrayList<>();
        final List<String> permissions = new ArrayList<>();
        final Player player;

        Fake(String name, boolean admin) {
            this.name = name;
            player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> id;
                        case "getName", "getDisplayName" -> name;
                        case "displayName", "name" -> Component.text(name);
                        case "getPlayer" -> proxy;
                        case "getServer" -> Bukkit.getServer();
                        case "isOnline", "isConnected", "isValid", "hasPlayedBefore" -> true;
                        case "isDead", "isOp" -> false;
                        case "hasPermission", "isPermissionSet" -> {
                            String permission = args[0] instanceof String text ? text : ((org.bukkit.permissions.Permission) args[0]).getName();
                            permissions.add(permission);
                            yield admin && (permission.equals(RELOAD) || permission.equals("planetry.admin.sendmail"));
                        }
                        case "getLevel" -> 17;
                        case "getExp" -> .5f;
                        case "getTotalExperience" -> 321;
                        case "setLevel", "setExp", "setTotalExperience", "giveExp", "giveExpLevels", "setExperienceLevelAndProgress" ->
                                throw new IllegalStateException("Language reload changed vanilla XP via " + method.getName());
                        case "sendExperienceChange" -> { check(Bukkit.isPrimaryThread(), "XP display sent off main thread"); yield null; }
                        case "sendMessage" -> { append(messages, components, args); yield null; }
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "LanguageProbe[" + name + "," + id + "]";
                        default -> defaultValue(method.getReturnType());
                    });
        }
        void clear() { messages.clear(); components.clear(); }
    }

    private static void append(List<String> messages, List<Component> components, Object[] args) {
        check(Bukkit.isPrimaryThread(), "Language message sent off main thread");
        if (args == null) return;
        for (Object arg : args) {
            if (arg instanceof Component component) { messages.add(plain(component)); components.add(component); }
            else if (arg instanceof String[] strings) messages.addAll(List.of(strings));
            else if (arg instanceof String string) messages.add(string);
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

    /** Isolated nested type keeps no-hooks startup independent of the optional PAPI API. */
    private static final class Hooks {
        static void register() {
            check(new me.clip.placeholderapi.expansion.PlaceholderExpansion() {
                @Override public String getIdentifier() { return "langprobe"; }
                @Override public String getAuthor() { return "PlanetryCore runtime acceptance"; }
                @Override public String getVersion() { return "1"; }
                @Override public boolean persist() { return true; }
                @Override public String onRequest(org.bukkit.OfflinePlayer player, String params) {
                    check(Bukkit.isPrimaryThread(), "Language placeholder resolved off main thread");
                    check(player != null, "Language placeholder expanded without a player recipient");
                    return switch (params) {
                        case "name" -> player.getName();
                        case "markup" -> "&6" + MARKUP;
                        default -> null;
                    };
                }
            }.register(), "Controlled expansion did not register with actual PlaceholderAPI");
        }
    }
}
