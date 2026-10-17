package dev.flint.planetry.core.journal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.*;

import dev.flint.planetry.core.PlanetryCore;
import dev.flint.planetry.core.journal.storage.JournalStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalServiceTest {
    private static final UUID ID = UUID.fromString("2362a0af-04a5-42c6-8255-6badb58dd741");

    @Test void administrativePermissionUsesExactNodeAndConsoleBypass() {
        CommandSender sender = mock(CommandSender.class);
        assertFalse(JournalService.allowed(sender));
        when(sender.hasPermission("planetry.admin.journal")).thenReturn(true);
        assertTrue(JournalService.allowed(sender));
        verify(sender, times(2)).hasPermission("planetry.admin.journal");
        assertTrue(JournalService.allowed(mock(ConsoleCommandSender.class)));
    }

    @Test void invalidStartupSettingsCloseTheAlreadyOpenedStore(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("journal.yml"), "categories: {}\n");
        PlanetryCore plugin = mock(PlanetryCore.class); when(plugin.getDataFolder()).thenReturn(directory.toFile());
        var store = new MemoryStore();
        assertThrows(IllegalArgumentException.class, () -> new JournalService(plugin, store, (type, id) -> null));
        assertTrue(store.closed);
    }

    @Test void recognizedPlaceholdersUseLoadedSnapshotsAndPreserveUnderscores() throws Exception {
        try (var f = new Fixture()) {
            assertEquals("", f.service.placeholder(ID, "journal_moon_crystal_found"));
            assertNull(f.service.placeholder(ID, "journal_unknown_found"));
            f.next();
            assertEquals("false", f.service.placeholder(ID, "journal_moon_crystal_found"));
            assertEquals("0", f.service.placeholder(ID, "journal_mining_unlocked"));
            assertEquals("1", f.service.placeholder(ID, "journal_mining_max"));
            assertEquals("", f.service.placeholder(null, "journal_mining_max"));
            assertNull(f.service.placeholder(ID, "journal_unknown_max"));
            assertNull(f.service.placeholder(ID, "level"));
            f.service.quit(new PlayerQuitEvent(f.player, Component.empty()));
            assertEquals("", f.service.placeholder(ID, "journal_moon_crystal_found"));
        }
    }

    @Test void unlockCannotReportSuccessBeforeCommitAndRepeatedTriggersCoalesce() throws Exception {
        try (var f = new Fixture()) {
            f.next(); f.store.block = new CountDownLatch(1); f.store.entered = new CountDownLatch(1);
            f.service.harvest(f.player, "moon_node");
            assertTrue(f.store.entered.await(5, TimeUnit.SECONDS));
            f.service.harvest(f.player, "moon_node");
            assertEquals("false", f.service.placeholder(ID, "journal_moon_crystal_found"));
            verify(f.player, never()).sendMessage(any(Component.class));
            f.store.block.countDown(); f.next();
            assertEquals("true", f.service.placeholder(ID, "journal_moon_crystal_found"));
            assertEquals("1", f.service.placeholder(ID, "journal_mining_unlocked"));
            assertEquals(1, f.store.writes);
            f.service.harvest(f.player, "moon_node"); assertEquals(1, f.store.writes);
        }
    }

    @Test void failedWriteDoesNotUnlockAndNextTriggerCanRetry() throws Exception {
        try (var f = new Fixture()) {
            f.next(); f.store.fail = true; f.service.killed(f.player, "MoonGuardian"); f.next();
            assertEquals("false", f.service.placeholder(ID, "journal_moon_crystal_found"));
            verify(f.player).sendMessage(any(Component.class));
            f.store.fail = false; f.service.killed(f.player, "MoonGuardian"); f.next();
            assertEquals("true", f.service.placeholder(ID, "journal_moon_crystal_found"));
            assertEquals(2, f.store.writes);
        }
    }

    @Test void oldSessionCallbackCannotNotifyOrPopulateARejoinedSession() throws Exception {
        try (var f = new Fixture()) {
            f.next(); f.store.block = new CountDownLatch(1); f.store.entered = new CountDownLatch(1);
            f.service.harvest(f.player, "moon_node"); assertTrue(f.store.entered.await(5, TimeUnit.SECONDS));
            f.service.quit(new PlayerQuitEvent(f.player, Component.empty()));
            Player rejoined = f.player(); f.service.join(new PlayerJoinEvent(rejoined, Component.empty()));
            f.store.block.countDown(); f.next(); // old write callback
            assertEquals("", f.service.placeholder(ID, "journal_moon_crystal_found"));
            verify(f.player, never()).sendMessage(any(Component.class)); verify(rejoined, never()).sendMessage(any(Component.class));
            f.next(); // current session's new read
            assertEquals("true", f.service.placeholder(ID, "journal_moon_crystal_found"));
        }
    }

    @Test void deniedAdminCommandCannotWriteAndAllowedRemoveIsDurable() throws Exception {
        try (var f = new Fixture()) {
            f.next();
            f.service.onCommand(f.player, f.admin, "journaladmin", new String[]{ID.toString(), "add", "moon_crystal"});
            assertEquals(0, f.store.writes);
            when(f.player.hasPermission("planetry.admin.journal")).thenReturn(true);
            f.service.onCommand(f.player, f.admin, "journaladmin", new String[]{ID.toString(), "add", "moon_crystal"}); f.next();
            assertEquals("true", f.service.placeholder(ID, "journal_moon_crystal_found"));
            f.service.onCommand(f.player, f.admin, "journaladmin", new String[]{ID.toString(), "remove", "moon_crystal"}); f.next();
            assertEquals("false", f.service.placeholder(ID, "journal_moon_crystal_found"));
            assertEquals(2, f.store.writes);
        }
    }

    @Test void publicCategoryCommandExecutesOnlyThePlayersOwnFixedConsoleOpen() throws Exception {
        try (var f = new Fixture()) {
            f.next();
            f.service.onCommand(f.player, f.journal, "journal", new String[]{"mining"});
            verify(f.journal).execute(eq(f.console), eq("journal"), aryEq(new String[]{"open", ID.toString(), "mining"}));
            clearInvocations(f.journal);
            f.service.onCommand(f.player, f.journal, "journal", new String[]{"open", UUID.randomUUID().toString(), "mining"});
            verify(f.journal, never()).execute(any(), anyString(), any());
            assertEquals(0, f.store.writes);
            f.service.onCommand(f.player, f.journal, "journal", new String[]{"mining;op"});
            verify(f.journal, never()).execute(any(), anyString(), any());
        }
    }

    @Test void periodicReadFailureInvalidatesCacheAndSuccessfulRefreshRestoresIt() throws Exception {
        try (var f = new Fixture()) {
            f.next(); assertEquals("false", f.service.placeholder(ID, "journal_moon_crystal_found"));
            f.store.readFail = true; f.refresh.run(); f.next();
            assertEquals("", f.service.placeholder(ID, "journal_moon_crystal_found"));
            f.store.readFail = false; f.refresh.run(); f.next();
            assertEquals("false", f.service.placeholder(ID, "journal_moon_crystal_found"));
        }
    }

    @Test void shutdownMakesAlreadyQueuedCallbacksInertAndClosesStorage() throws Exception {
        try (var f = new Fixture()) {
            Runnable callback = f.callbacks.poll(5, TimeUnit.SECONDS); assertNotNull(callback);
            f.service.close(); callback.run();
            assertEquals("", f.service.placeholder(ID, "journal_moon_crystal_found"));
            assertTrue(f.store.closed); verify(f.timer).cancel();
        }
    }

    private static final class MemoryStore implements JournalStore {
        final Map<UUID, Set<String>> data = new HashMap<>();
        volatile boolean fail, readFail, closed;
        volatile int writes;
        CountDownLatch block, entered;
        @Override public synchronized Set<String> found(UUID id) throws IOException {
            if (readFail) throw new IOException("injected read failure");
            return Set.copyOf(data.getOrDefault(id, Set.of()));
        }
        @Override public boolean setFound(UUID id, String entry, boolean found) throws IOException {
            writes++;
            if (entered != null) entered.countDown();
            if (block != null) try { if (!block.await(5, TimeUnit.SECONDS)) throw new IOException("test gate expired"); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
            if (fail) throw new IOException("injected failure");
            synchronized (this) { var values = data.computeIfAbsent(id, ignored -> new HashSet<>()); return found ? values.add(entry) : values.remove(entry); }
        }
        @Override public void close() { closed = true; }
    }
    private static final class Fixture implements AutoCloseable {
        final JavaPlugin plugin = mock(JavaPlugin.class);
        final Server server = mock(Server.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        final PluginCommand journal = mock(PluginCommand.class), admin = mock(PluginCommand.class);
        final ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        final BukkitTask timer = mock(BukkitTask.class);
        final LinkedBlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        final MemoryStore store = new MemoryStore();
        final Player player = player();
        final JournalService service;
        Runnable refresh;
        Fixture() {
            when(plugin.getServer()).thenReturn(server); when(plugin.getLogger()).thenReturn(Logger.getLogger("journal-unit"));
            when(plugin.getCommand("journal")).thenReturn(journal); when(plugin.getCommand("journaladmin")).thenReturn(admin);
            when(journal.getName()).thenReturn("journal"); when(admin.getName()).thenReturn("journaladmin");
            when(server.isPrimaryThread()).thenReturn(true); when(server.getScheduler()).thenReturn(scheduler);
            when(server.getPluginManager()).thenReturn(mock(PluginManager.class)); doReturn(List.of(player)).when(server).getOnlinePlayers();
            when(server.getConsoleSender()).thenReturn(console);
            when(server.getOfflinePlayer(ID)).thenReturn(player);
            when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> { callbacks.add(call.getArgument(1)); return mock(BukkitTask.class); });
            when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(100L), eq(100L))).thenAnswer(call -> { refresh = call.getArgument(1); return timer; });
            var config = JournalSettingsTest.config(); JournalSettingsTest.entry(config, "mining", "moon_crystal");
            config.set("categories.mining.entries.moon_crystal.harvest-nodes", List.of("moon_node"));
            config.set("categories.mining.entries.moon_crystal.mythic-mobs", List.of("MoonGuardian"));
            service = new JournalService(plugin, store, JournalSettings.read(config), (type, id) -> null); service.start();
        }
        Player player() { Player value = mock(Player.class); when(value.getUniqueId()).thenReturn(ID); when(value.getName()).thenReturn("Alice"); when(value.isOnline()).thenReturn(true); return value; }
        void next() throws Exception { Runnable action = callbacks.poll(5, TimeUnit.SECONDS); assertNotNull(action, "storage callback"); action.run(); }
        @Override public void close() { if (store.block != null) store.block.countDown(); service.close(); }
    }
}
