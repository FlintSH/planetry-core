package dev.flint.planetry.core.harvest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;

/** Local world state, not player inventory. Every replacement is forced before callers mutate a block. */
public final class NodeStore implements AutoCloseable {
    public record Key(UUID world, int x, int y, int z) {
        public Key { if (world == null || Math.abs((long)x) > 30_000_000 || Math.abs((long)z) > 30_000_000 || y < -4096 || y > 4096) throw new IllegalArgumentException("Invalid node location"); }
    }
    /** previous is a durable write-ahead intent, or empty after a confirmed world mutation. */
    public record Node(Key key, String profile, String source, String expected, String previous,
                       long started, long deadline, boolean suspended) {
        public Node {
            if (key == null || profile == null || !profile.matches("[A-Za-z0-9_:-]{1,128}")) throw new IllegalArgumentException("Invalid node profile");
            for (String data : new String[]{source, expected, previous}) if (data == null || data.length() > 2048) throw new IllegalArgumentException("Invalid node block data");
            if (source.isEmpty() || expected.isEmpty() || started < 0 || deadline < 0 || deadline > 0 && deadline <= started) throw new IllegalArgumentException("Invalid node cooldown");
        }
        public boolean cooling() { return deadline > 0; }
        public Node intent(String next, long start, long end) { return new Node(key, profile, source, next, expected, start, end, suspended); }
        public Node confirmed() { return new Node(key, profile, source, expected, "", started, deadline, suspended); }
        public Node suspend() { return new Node(key, profile, source, expected, "", started, deadline, true); }
    }
    private static final int MAGIC = 0x504E4831;
    private final Path file;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private Map<Key, Node> nodes;

    public NodeStore(Path folder) throws IOException {
        Files.createDirectories(folder);
        file = folder.resolve("harvest-nodes.dat");
        lockChannel = FileChannel.open(folder.resolve("harvest-nodes.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired = lockChannel.tryLock(); }
        catch (RuntimeException | IOException error) { lockChannel.close(); throw error; }
        if (acquired == null) { lockChannel.close(); throw new IOException("Harvest data is already open"); }
        lock = acquired;
        try { nodes = Files.exists(file) ? read(file) : Map.of(); }
        catch (RuntimeException | IOException error) { close(); throw error; }
    }

    public Map<Key, Node> nodes() { return nodes; }
    public void replace(Map<Key, Node> replacement) throws IOException {
        if (replacement.size() > 10000) throw new IOException("Harvest node limit exceeded");
        Map<Key, Node> copy = Map.copyOf(replacement);
        byte[] bytes = encode(copy);
        if (bytes.length > 32_000_000) throw new IOException("Harvest state exceeds the supported size");
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        // An unsupported atomic move must fail closed, never fall back to delete-and-replace.
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        try (FileChannel directory = FileChannel.open(file.getParent(), StandardOpenOption.READ)) { directory.force(true); }
        nodes = copy;
    }
    private static byte[] encode(Map<Key, Node> nodes) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(payload)) {
            out.writeInt(nodes.size());
            for (Node node : nodes.values()) {
                Key key = node.key(); out.writeLong(key.world().getMostSignificantBits()); out.writeLong(key.world().getLeastSignificantBits());
                out.writeInt(key.x()); out.writeInt(key.y()); out.writeInt(key.z());
                out.writeUTF(node.profile()); out.writeUTF(node.source()); out.writeUTF(node.expected()); out.writeUTF(node.previous());
                out.writeLong(node.started()); out.writeLong(node.deadline()); out.writeBoolean(node.suspended());
            }
        }
        CRC32 crc = new CRC32(); crc.update(payload.toByteArray());
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(result)) { out.writeInt(MAGIC); out.writeLong(crc.getValue()); out.write(payload.toByteArray()); }
        return result.toByteArray();
    }
    private static Map<Key, Node> read(Path file) throws IOException {
        long size = Files.size(file);
        if (size < 16 || size > 32_000_000) throw new IOException("Invalid harvest state size");
        byte[] bytes = Files.readAllBytes(file);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC) throw new IOException("Unknown harvest state format");
            long checksum = in.readLong(); CRC32 crc = new CRC32(); crc.update(bytes, 12, bytes.length - 12);
            if (checksum != crc.getValue()) throw new IOException("Harvest state checksum mismatch");
            int count = in.readInt(); if (count < 0 || count > 10000) throw new IOException("Invalid harvest node count");
            Map<Key, Node> result = new LinkedHashMap<>();
            for (int index = 0; index < count; index++) {
                Key key = new Key(new UUID(in.readLong(), in.readLong()), in.readInt(), in.readInt(), in.readInt());
                Node node = new Node(key, in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readLong(), in.readLong(), in.readBoolean());
                if (result.put(key, node) != null) throw new IOException("Duplicate harvest node");
            }
            if (in.read() != -1) throw new IOException("Trailing harvest state data");
            return Map.copyOf(result);
        } catch (IllegalArgumentException error) { throw new IOException("Malformed harvest node", error); }
    }
    @Override public void close() throws IOException { try { if (lock.isValid()) lock.release(); } finally { lockChannel.close(); } }
}
