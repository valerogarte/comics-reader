package com.valerogarte.comicsreader.sftp;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.ChannelSubsystem;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.UIKeyboardInteractive;
import com.jcraft.jsch.UserInfo;
import java.io.File;
import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Plain JSch wrapper (no Android dependencies) that keeps one SSH session per set of credentials. */
public final class SftpClient {

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int MAX_RANGE_SIZE = 4 * 1024 * 1024;
    private static final int MAX_SFTP_PACKET = 256 * 1024;
    private static final int SERVER_ALIVE_INTERVAL_MS = 15000;
    private static final int CHANNEL_INPUT_BUFFER = 2 * 1024 * 1024;
    private static final int MAX_IDLE_READERS = 4;
    private static final ScheduledThreadPoolExecutor CHANNEL_WATCHDOG = watchdogExecutor();
    // Synology blocks an IP after 10 failed logins in 5 minutes: never retry rejected credentials within that window.
    private static final long AUTH_FAILURE_COOLDOWN_MS = 5 * 60 * 1000;

    public static final class Credentials {

        final String host;
        final int port;
        final String username;
        final String password;

        public Credentials(String host, int port, String username, String password) {
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password;
        }

        @Override public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Credentials)) return false;
            Credentials other = (Credentials) value;
            return port == other.port && Objects.equals(host, other.host)
                && Objects.equals(username, other.username) && Objects.equals(password, other.password);
        }

        @Override public int hashCode() { return Objects.hash(host, port, username, password); }
    }

    public static final class Entry {

        public final String name;
        public final long size;
        public final boolean directory;
        public final long modifiedMillis;

        Entry(String name, long size, boolean directory, long modifiedMillis) {
            this.name = name;
            this.size = size;
            this.directory = directory;
            this.modifiedMillis = modifiedMillis;
        }
    }

    public interface ProgressListener {
        void onProgress(long transferredBytes);
    }

    private interface ChannelAction<T> {
        T run(ChannelSftp channel, ChannelGuard guard) throws JSchException, SftpException;
    }

    private final File knownHostsFile;
    private final int rangeTimeoutMillis;
    private final int inactivityTimeoutMillis;
    // Equality compares every credential, including the password: a hash collision must never
    // reuse a different login's authenticated session or its authentication cooldown.
    private final Map<Credentials, Session> sessions = new HashMap<>();
    private final Map<Credentials, Long> authFailureUntil = new HashMap<>();
    // Guarded by sessions; each idle reader belongs to the cached session of its credentials.
    private final Map<Credentials, ArrayDeque<ReaderChannel>> idleReaders = new HashMap<>();

    public SftpClient(File knownHostsFile) {
        this(knownHostsFile, 15000, 30000);
    }

    /** Shorter deadlines are useful for transports on constrained links and deterministic QA. */
    public SftpClient(File knownHostsFile, int rangeTimeoutMillis) {
        this(knownHostsFile, rangeTimeoutMillis, rangeTimeoutMillis);
    }

    public SftpClient(File knownHostsFile, int rangeTimeoutMillis, int inactivityTimeoutMillis) {
        if (rangeTimeoutMillis < 1 || rangeTimeoutMillis > 15000) throw new IllegalArgumentException("Invalid SFTP range timeout");
        if (inactivityTimeoutMillis < 1 || inactivityTimeoutMillis > 30000) throw new IllegalArgumentException("Invalid SFTP inactivity timeout");
        this.knownHostsFile = knownHostsFile;
        this.rangeTimeoutMillis = rangeTimeoutMillis;
        this.inactivityTimeoutMillis = inactivityTimeoutMillis;
    }

    /** Shelf operations are read-only, confined to the configured folder and propagate listing errors. */
    public List<Entry> listShelf(Credentials credentials, String rootPath, String relativePath)
        throws JSchException, SftpException {
        return withChannel(credentials, (channel, guard) -> {
            String root = channel.realpath(normalizeShelfPath(rootPath));
            guard.activity();
            String path = shelfPath(channel, root, relativePath);
            guard.activity();
            List<ChannelSftp.LsEntry> rawEntries = new ArrayList<>();
            SftpException[] failure = { null };
            // Receive progress while READDIR is being decoded. A large, active directory is
            // allowed to outlive the inactivity timeout, just like an active download.
            channel.ls(literalPath(path), rawEntry -> {
                try {
                    guard.activity();
                    rawEntries.add(rawEntry);
                    return ChannelSftp.LsEntrySelector.CONTINUE;
                } catch (SftpException error) {
                    failure[0] = error;
                    return ChannelSftp.LsEntrySelector.BREAK;
                }
            });
            if (failure[0] != null) throw failure[0];
            List<Entry> entries = new ArrayList<>();
            for (ChannelSftp.LsEntry rawEntry : rawEntries) {
                guard.activity();
                String name = rawEntry.getFilename();
                if (!isShelfEntryName(name)) continue;
                SftpATTRS attrs = rawEntry.getAttrs();
                if (attrs.isLink()) {
                    try {
                        String target = channel.realpath(join(path, name));
                        guard.activity();
                        if (!insideRoot(root, target)) continue;
                        attrs = channel.stat(literalPath(target));
                        guard.activity();
                    } catch (SftpException ignored) {
                        guard.check();
                        continue;
                    }
                }
                if (!attrs.isDir() && !attrs.isReg()) continue;
                entries.add(new Entry(name, attrs.getSize(), attrs.isDir(), attrs.getMTime() * 1000L));
            }
            return entries;
        });
    }

    /** Counts direct regular-file children without retaining their directory entries or opening books. */
    public int countShelfFiles(Credentials credentials, String rootPath, String relativePath)
        throws JSchException, SftpException {
        return withChannel(credentials, (channel, guard) -> {
            String root = channel.realpath(normalizeShelfPath(rootPath));
            guard.activity();
            String path = shelfPath(channel, root, relativePath);
            guard.activity();
            long[] count = { 0 };
            ArrayDeque<String> links = new ArrayDeque<>();
            SftpException[] failure = { null };
            channel.ls(literalPath(path), rawEntry -> {
                try {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new SftpException(ChannelSftp.SSH_FX_FAILURE, "SFTP listing cancelled",
                            new IOException("SFTP operation cancelled"));
                    }
                    guard.activity();
                    String name = rawEntry.getFilename();
                    if (!isShelfEntryName(name)) return ChannelSftp.LsEntrySelector.CONTINUE;
                    SftpATTRS attrs = rawEntry.getAttrs();
                    // The selector runs while JSch decodes READDIR; defer other requests on this channel.
                    if (attrs.isLink()) links.addLast(name);
                    else if (attrs.isReg()) count[0]++;
                    return ChannelSftp.LsEntrySelector.CONTINUE;
                } catch (SftpException error) {
                    failure[0] = error;
                    return ChannelSftp.LsEntrySelector.BREAK;
                }
            });
            if (failure[0] != null) throw failure[0];
            for (String name : links) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new SftpException(ChannelSftp.SSH_FX_FAILURE, "SFTP listing cancelled",
                        new IOException("SFTP operation cancelled"));
                }
                guard.activity();
                try {
                    String target = channel.realpath(join(path, name));
                    guard.activity();
                    if (!insideRoot(root, target)) continue;
                    if (channel.stat(literalPath(target)).isReg()) count[0]++;
                    guard.activity();
                } catch (SftpException error) {
                    if (error.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw error;
                }
            }
            if (count[0] > Integer.MAX_VALUE) {
                throw new SftpException(ChannelSftp.SSH_FX_FAILURE, "Too many SFTP files to count");
            }
            return (int) count[0];
        });
    }

    /** Reads only the requested bytes without creating a temporary comic file. */
    public byte[] readShelfRange(Credentials credentials, String rootPath, String relativePath, long offset, int length)
        throws JSchException, SftpException {
        validateRange(offset, length);
        try (RangeReader reader = openShelfReader(credentials, rootPath, relativePath)) {
            return reader.read(offset, length);
        }
    }

    /**
     * A book preview resolves its confined path and reads through an SFTP subsystem that is kept
     * for the next preview: the server does not start a new SFTP process for every book.
     */
    public RangeReader openShelfReader(Credentials credentials, String rootPath, String relativePath)
        throws JSchException, SftpException {
        String root = normalizeShelfPath(rootPath);
        String relative = normalizeShelfPath(relativePath);
        ReaderChannel idle = takeIdleReader(credentials);
        if (idle != null) {
            try {
                return new RangeReader(this, credentials, idle, root, relative, rangeTimeoutMillis);
            } catch (IOException stale) {
                // The server may have closed an idle subsystem; retry once on a new one.
                if (Thread.currentThread().isInterrupted()) {
                    throw new SftpException(ChannelSftp.SSH_FX_FAILURE, "SFTP operation cancelled", stale);
                }
            }
        }
        ReaderChannel connection = openReaderChannel(credentials);
        try {
            return new RangeReader(this, credentials, connection, root, relative, rangeTimeoutMillis);
        } catch (IOException error) {
            throw new SftpException(ChannelSftp.SSH_FX_FAILURE, "Could not open SFTP byte range reader", error);
        }
    }

    private ReaderChannel openReaderChannel(Credentials credentials) throws JSchException, SftpException {
        Session session = getSession(credentials);
        try {
            try {
                return ReaderChannel.open(session, rangeTimeoutMillis);
            } catch (JSchException e) {
                if (e instanceof ChannelDeadlineException || Thread.currentThread().isInterrupted()) throw e;
                // Same policy as withChannel: reconnect a dropped session once, never retry a login.
                dropSession(credentials);
                return ReaderChannel.open(getSession(credentials), rangeTimeoutMillis);
            }
        } catch (IOException error) {
            throw new SftpException(ChannelSftp.SSH_FX_FAILURE, "Could not open SFTP byte range reader", error);
        }
    }

    private ReaderChannel takeIdleReader(Credentials credentials) {
        List<ReaderChannel> stale = new ArrayList<>();
        try {
            synchronized (sessions) {
                ArrayDeque<ReaderChannel> idle = idleReaders.get(credentials);
                Session current = sessions.get(credentials);
                while (idle != null && !idle.isEmpty()) {
                    ReaderChannel reader = idle.pollFirst();
                    if (reader.session == current && reader.usable()) return reader;
                    stale.add(reader);
                }
                return null;
            }
        } finally {
            for (ReaderChannel reader : stale) reader.disconnect();
        }
    }

    private void releaseReader(Credentials credentials, ReaderChannel reader) {
        synchronized (sessions) {
            if (reader.session == sessions.get(credentials) && reader.usable()) {
                ArrayDeque<ReaderChannel> idle = idleReaders.get(credentials);
                if (idle == null) idleReaders.put(credentials, idle = new ArrayDeque<>());
                if (idle.size() < MAX_IDLE_READERS) {
                    idle.addFirst(reader);
                    return;
                }
            }
        }
        reader.disconnect();
    }

    private static void validateRange(long offset, int length) {
        if (offset < 0 || offset > 9007199254740991L || length < 0 || length > MAX_RANGE_SIZE
            || offset > 9007199254740991L - length) {
            throw new IllegalArgumentException("Invalid SFTP byte range");
        }
    }

    /** One SFTP v3 subsystem. While idle it holds no file handle and has no request pending. */
    private static final class ReaderChannel {
        final Session session;
        final ChannelSubsystem channel;
        final InputStream input;
        final DataOutputStream output;
        private int requestId = 1;

        private ReaderChannel(Session session, ChannelSubsystem channel, InputStream input, DataOutputStream output) {
            this.session = session; this.channel = channel; this.input = input; this.output = output;
        }

        static ReaderChannel open(Session session, int timeoutMillis) throws JSchException, SftpException, IOException {
            ChannelSubsystem channel = (ChannelSubsystem) session.openChannel("subsystem");
            // JSch defaults to 16 KiB; fit one 240 KiB SFTP response in an SSH packet.
            channel.setLocalPacketSize(252 * 1024);
            channel.setSubsystem("sftp");
            channel.setWantReply(true);
            ChannelGuard guard = new ChannelGuard(channel, timeoutMillis);
            try {
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
                InputStream input = channel.getInputStream();
                DataOutputStream output = new DataOutputStream(channel.getOutputStream());
                channel.connect(Math.min(CONNECT_TIMEOUT_MS, timeoutMillis));
                sendSftpPacket(output, 1, 3, new byte[0]); // INIT, client version 3.
                DataInputStream version = receiveSftpPacket(input, channel, deadline);
                if (version.readUnsignedByte() != 2 || version.readInt() != 3) throw new IOException("Unsupported SFTP version");
                guard.check();
                return new ReaderChannel(session, channel, input, output);
            } catch (JSchException | SftpException | IOException | RuntimeException | Error error) {
                channel.disconnect();
                if (guard.expired || guard.cancelled) throw new ChannelDeadlineException("SFTP channel opening stopped", error);
                throw error;
            } finally { guard.close(); }
        }

        int send(int type, byte[] payload) throws IOException {
            if (++requestId <= 0) requestId = 1;
            sendSftpPacket(output, type, requestId, payload);
            return requestId;
        }

        DataInputStream call(int type, byte[] payload, int expectedType, long deadline) throws IOException, SftpException {
            return receiveSftpResponse(input, send(type, payload), expectedType, false, channel, deadline);
        }

        String realpath(String path, long deadline) throws IOException, SftpException {
            DataInputStream name = call(16, sftpString(path), 104, deadline);
            if (name.readInt() < 1) throw new IOException("Empty SFTP path resolution");
            return new String(readSftpString(name, 16384), StandardCharsets.UTF_8);
        }

        boolean usable() {
            return session.isConnected() && channel.isConnected() && !channel.isClosed() && !channel.isEOF();
        }

        void disconnect() { channel.disconnect(); }
    }

    private static byte[] sftpString(String value) throws IOException {
        return sftpString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sftpString(byte[] bytes) throws IOException {
        if (bytes.length > 16384) throw new IOException("SFTP path too long");
        ByteArrayOutputStream result = new ByteArrayOutputStream(bytes.length + 4);
        writeSftpString(new DataOutputStream(result), bytes);
        return result.toByteArray();
    }

    /** Exact SFTP v3 reads, pipelined without fetching bytes outside the requested range. */
    public static final class RangeReader implements Closeable {
        private static final int PIPELINE_SIZE = 4;
        private static final int READ_SIZE = 240 * 1024;
        public final long size;
        public final long modifiedMillis;
        private final int timeoutMillis;
        private final SftpClient owner;
        private final Credentials credentials;
        private final ReaderChannel connection;
        private final ChannelSubsystem channel;
        private final InputStream input;
        private final DataOutputStream output;
        private final byte[] handle;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile boolean reading;
        private volatile boolean reusable = true;

        private RangeReader(SftpClient owner, Credentials credentials, ReaderChannel connection, String root, String relative,
            int timeoutMillis) throws SftpException, IOException {
            this.owner = owner;
            this.credentials = credentials;
            this.connection = connection;
            this.timeoutMillis = timeoutMillis;
            channel = connection.channel;
            input = connection.input;
            output = connection.output;
            ChannelGuard guard = new ChannelGuard(channel, timeoutMillis);
            try {
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
                // Same confinement as shelfPath: resolve the root, then the file inside it.
                String realRoot = connection.realpath(root, deadline);
                guard.activity();
                String path = connection.realpath("/".equals(relative) ? realRoot : join(realRoot, relative.substring(1)), deadline);
                if (!insideRoot(realRoot, path)) throw new IllegalArgumentException("SFTP path is outside the configured folder");
                guard.activity();
                DataInputStream attributes = connection.call(17, sftpString(path), 105, deadline); // STAT follows links.
                int flags = attributes.readInt();
                long fileSize = (flags & 1) != 0 ? attributes.readLong() : -1;
                if ((flags & 2) != 0) { attributes.readInt(); attributes.readInt(); }
                int permissions = (flags & 4) != 0 ? attributes.readInt() : 0;
                int modified = 0;
                if ((flags & 8) != 0) { attributes.readInt(); modified = attributes.readInt(); }
                if ((flags & 4) == 0 || (permissions & 0xf000) != 0x8000) throw new IllegalArgumentException("Expected an SFTP file");
                if (fileSize < 0) throw new IllegalArgumentException("Invalid SFTP file size");
                size = fileSize;
                modifiedMillis = modified * 1000L; // Same signed seconds as SftpATTRS.getMTime in listings.
                guard.activity();
                ByteArrayOutputStream openBytes = new ByteArrayOutputStream();
                DataOutputStream open = new DataOutputStream(openBytes);
                open.write(sftpString(path));
                open.writeInt(1); // SSH_FXF_READ only: no create, append or write flags.
                open.writeInt(0); // Empty attributes.
                handle = readSftpString(connection.call(3, openBytes.toByteArray(), 102, deadline), 256);
                if (handle.length == 0) throw new IOException("Invalid SFTP file handle");
                guard.check();
            } catch (SftpException | IOException | RuntimeException | Error error) {
                discard();
                throw error;
            } finally { guard.close(); }
        }

        /** Calls may come from different document callback threads; each call has its own deadline. */
        public synchronized byte[] read(long offset, int length) throws SftpException {
            reading = true;
            try {
                validateRange(offset, length);
                if (closed.get()) throw new SftpException(ChannelSftp.SSH_FX_FAILURE, "SFTP range reader is closed");
                int count = (int) Math.min(length, Math.max(0, size - offset));
                if (count == 0) return new byte[0];
                ChannelGuard guard = new ChannelGuard(channel, timeoutMillis);
                try {
                    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
                    byte[] result = readRange(offset, count, deadline);
                    guard.check();
                    return result;
                } catch (IOException error) {
                    discard();
                    throw new SftpException(ChannelSftp.SSH_FX_FAILURE, "Could not read SFTP byte range", error);
                } catch (SftpException | RuntimeException | Error error) {
                    discard();
                    throw error;
                } finally { guard.close(); }
            } finally { reading = false; }
        }

        private byte[] readRange(long offset, int count, long deadline) throws IOException, SftpException {
            byte[] result = new byte[count];
            BitSet received = new BitSet(count);
            Map<Integer, PendingRead> pending = new HashMap<>();
            ArrayDeque<PendingRead> retries = new ArrayDeque<>();
            long nextOffset = offset;
            long end = offset + count;
            while (true) {
                while (pending.size() < PIPELINE_SIZE) {
                    PendingRead request = retries.pollFirst();
                    if (request == null) {
                        if (nextOffset >= end) break;
                        int length = (int) Math.min(READ_SIZE, end - nextOffset);
                        request = new PendingRead(nextOffset, length);
                        nextOffset += length;
                    } else if (request.offset >= end) continue;
                    else request = new PendingRead(request.offset, (int) Math.min(request.length, end - request.offset));
                    if (Thread.currentThread().isInterrupted()) throw new IOException("SFTP range cancelled");
                    if (System.nanoTime() >= deadline) throw new SocketTimeoutException("SFTP range deadline exceeded");
                    ByteArrayOutputStream payload = new ByteArrayOutputStream();
                    DataOutputStream read = new DataOutputStream(payload);
                    writeSftpString(read, handle);
                    read.writeLong(request.offset);
                    read.writeInt(request.length);
                    pending.put(connection.send(5, payload.toByteArray()), request);
                }
                if (pending.isEmpty()) break;
                DataInputStream response = receiveSftpPacket(input, channel, deadline);
                int type = response.readUnsignedByte();
                PendingRead request = pending.remove(response.readInt());
                if (request == null) throw new IOException("Unexpected SFTP response identifier");
                if (type == 101) {
                    int status = response.readInt();
                    if (status != ChannelSftp.SSH_FX_EOF) throw new SftpException(status, "SFTP range request failed");
                    end = Math.min(end, request.offset);
                } else if (type == 103) {
                    byte[] bytes = readSftpString(response, request.length);
                    boolean eof = false;
                    if (response.available() == 1) {
                        int flag = response.readUnsignedByte();
                        if (flag > 1) throw new IOException("Invalid SFTP end-of-file flag");
                        eof = flag == 1;
                    } else if (response.available() != 0) throw new IOException("Invalid SFTP data response");
                    if (bytes.length == 0 && !eof) throw new IOException("Empty SFTP data response");
                    int position = (int) (request.offset - offset);
                    System.arraycopy(bytes, 0, result, position, bytes.length);
                    received.set(position, position + bytes.length);
                    if (eof) end = Math.min(end, request.offset + bytes.length);
                    else if (bytes.length < request.length) {
                        retries.addFirst(new PendingRead(request.offset + bytes.length, request.length - bytes.length));
                    }
                } else throw new IOException("Unexpected SFTP response type");
            }
            int available = (int) (end - offset);
            if (received.nextClearBit(0) < available) throw new IOException("Incomplete SFTP range response");
            return available == count ? result : Arrays.copyOf(result, available);
        }

        /** After a failure, disconnecting the subsystem releases its handle without draining queued replies. */
        private void discard() {
            reusable = false;
            close();
        }

        /** A clean reader closes its handle and returns the subsystem for the next preview. */
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            // A concurrent read is aborted by disconnecting, as before; reuse needs an idle protocol state.
            if (!reusable || reading || Thread.currentThread().isInterrupted()) {
                connection.disconnect();
                return;
            }
            synchronized (this) {
                ChannelGuard guard = new ChannelGuard(channel, timeoutMillis);
                try {
                    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
                    connection.call(4, sftpString(handle), 101, deadline);
                    guard.check();
                } catch (IOException | SftpException | RuntimeException error) {
                    connection.disconnect();
                    return;
                } finally { guard.close(); }
            }
            owner.releaseReader(credentials, connection);
        }
    }

    private static final class PendingRead {
        final long offset;
        final int length;
        PendingRead(long offset, int length) { this.offset = offset; this.length = length; }
    }

    private static void sendSftpPacket(DataOutputStream output, int type, int id, byte[] payload) throws IOException {
        if (payload.length > MAX_SFTP_PACKET - 5) throw new IOException("SFTP request too large");
        output.writeInt(payload.length + 5);
        output.writeByte(type);
        output.writeInt(id);
        output.write(payload);
        output.flush();
    }

    private static DataInputStream receiveSftpPacket(InputStream input, ChannelSubsystem channel, long deadline) throws IOException {
        int length = new DataInputStream(new ByteArrayInputStream(readSftpBytes(input, 4, channel, deadline))).readInt();
        if (length < 5 || length > MAX_SFTP_PACKET) throw new IOException("Invalid SFTP packet length");
        return new DataInputStream(new ByteArrayInputStream(readSftpBytes(input, length, channel, deadline)));
    }

    private static byte[] readSftpBytes(InputStream input, int length, ChannelSubsystem channel, long deadline) throws IOException {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("SFTP range cancelled");
            if (System.nanoTime() >= deadline) throw new SocketTimeoutException("SFTP range deadline exceeded");
            int available = input.available();
            if (available == 0) {
                if (channel.isClosed() || channel.isEOF()) throw new EOFException("SFTP range channel closed");
                awaitPipe(input);
                continue;
            }
            int read = input.read(result, offset, Math.min(available, length - offset));
            if (read < 0) throw new EOFException("Incomplete SFTP response");
            offset += read;
            wakePipeWriter(input);
        }
        return result;
    }

    /**
     * JSch's session thread, shared by every channel, waits up to one second for room in a full
     * channel pipe and only a blocking reader wakes it. This reader polls to honour its deadline,
     * so it wakes the writer itself; the writer's flush wakes this wait as soon as data arrives.
     */
    private static void awaitPipe(InputStream input) throws IOException {
        synchronized (input) {
            input.notifyAll();
            try { if (input.available() == 0) input.wait(2); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("SFTP range cancelled", error); }
        }
    }

    private static void wakePipeWriter(InputStream input) {
        synchronized (input) { input.notifyAll(); }
    }

    private static DataInputStream receiveSftpResponse(InputStream input, int requestId, int expectedType, boolean allowEof,
        ChannelSubsystem channel, long deadline)
        throws IOException, SftpException {
        DataInputStream packet = receiveSftpPacket(input, channel, deadline);
        int type = packet.readUnsignedByte();
        if (packet.readInt() != requestId) throw new IOException("Unexpected SFTP response identifier");
        if (type == 101) {
            int status = packet.readInt();
            if (allowEof && status == ChannelSftp.SSH_FX_EOF) return null;
            if (status != ChannelSftp.SSH_FX_OK) throw new SftpException(status, "SFTP range request failed");
        }
        if (type != expectedType) throw new IOException("Unexpected SFTP response type");
        return packet;
    }

    private static void writeSftpString(DataOutputStream output, byte[] bytes) throws IOException {
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static byte[] readSftpString(DataInputStream input, int maximum) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximum || length > input.available()) throw new IOException("Invalid SFTP string length");
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return bytes;
    }

    /** Explicit local download. Resolves symlinks under the configured root and never writes to the server. */
    public long downloadShelf(Credentials credentials, String rootPath, String relativePath, File localFile, ProgressListener listener)
        throws JSchException, SftpException {
        boolean completed = false;
        try {
            long result = withChannel(credentials, (channel, guard) -> {
                String root = channel.realpath(normalizeShelfPath(rootPath));
                guard.activity();
                String path = shelfPath(channel, root, relativePath);
                guard.activity();
                SftpATTRS before = channel.stat(literalPath(path));
                guard.activity();
                if (!before.isReg()) throw new IllegalArgumentException("Expected an SFTP file");
                long transferred = 0;
                try (InputStream input = channel.get(literalPath(path)); FileOutputStream output = new FileOutputStream(localFile)) {
                    try {
                        byte[] buffer = new byte[65536];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            if (Thread.currentThread().isInterrupted()) throw new IOException("SFTP download cancelled");
                            guard.activity();
                            output.write(buffer, 0, count);
                            transferred += count;
                            if (listener != null) listener.onProgress(transferred);
                        }
                        output.getFD().sync();
                    } catch (IOException | RuntimeException | SftpException error) {
                        // JSch stream.close() drains outstanding READ responses. Close the channel
                        // first on cancellation/failure so cleanup cannot wait for a stalled server.
                        channel.disconnect();
                        throw error;
                    }
                } catch (IOException | RuntimeException e) {
                    throw new SftpException(ChannelSftp.SSH_FX_FAILURE, "Could not download SFTP file", e);
                }
                guard.activity();
                SftpATTRS after = channel.stat(literalPath(path));
                if (transferred != before.getSize() || after.getSize() != before.getSize() || after.getMTime() != before.getMTime()) {
                    throw new SftpException(ChannelSftp.SSH_FX_FAILURE, "SFTP file changed during download");
                }
                return transferred;
            });
            completed = true;
            return result;
        } finally {
            if (!completed) localFile.delete();
        }
    }

    private static String shelfPath(ChannelSftp channel, String root, String relativePath) throws SftpException {
        String relative = normalizeShelfPath(relativePath);
        String target = channel.realpath("/".equals(relative) ? root : join(root, relative.substring(1)));
        if (!insideRoot(root, target)) throw new IllegalArgumentException("SFTP path is outside the configured folder");
        return target;
    }

    private static boolean insideRoot(String root, String path) {
        return "/".equals(root) || path.equals(root) || path.startsWith(root + "/");
    }

    private static boolean isShelfEntryName(String name) {
        return name != null && !name.isEmpty() && !".".equals(name) && !"..".equals(name)
            && name.indexOf('/') < 0 && name.indexOf('\\') < 0 && !hasControlCharacters(name);
    }

    private static String normalizeShelfPath(String path) {
        if (path == null || path.indexOf('\\') >= 0 || hasControlCharacters(path)) {
            throw new IllegalArgumentException("Invalid SFTP path");
        }
        StringBuilder result = new StringBuilder();
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) continue;
            if (".".equals(segment) || "..".equals(segment)) throw new IllegalArgumentException("Invalid SFTP path");
            result.append('/').append(segment);
        }
        return result.length() == 0 ? "/" : result.toString();
    }

    private static boolean hasControlCharacters(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 32 || character == 127) return true;
        }
        return false;
    }

    public void disconnectAll() {
        synchronized (sessions) {
            for (Session session : sessions.values()) {
                session.disconnect();
            }
            sessions.clear();
            idleReaders.clear();
        }
    }

    private <T> T withChannel(Credentials credentials, ChannelAction<T> action) throws JSchException, SftpException {
        Session session = getSession(credentials);
        GuardedChannel opened;
        try {
            opened = openChannel(session);
        } catch (JSchException e) {
            if (e instanceof ChannelDeadlineException || Thread.currentThread().isInterrupted()) throw e;
            // A cached session may have been dropped by the server (sleep, network change): reconnect once.
            // Login failures are not retried here, so a wrong password never counts twice against Synology's auto-block.
            dropSession(credentials);
            opened = openChannel(getSession(credentials));
        }
        try {
            T result = action.run(opened.channel, opened.guard);
            opened.guard.check();
            return result;
        } catch (JSchException | SftpException error) {
            opened.guard.check();
            throw error;
        } finally {
            opened.guard.close();
            opened.channel.disconnect();
        }
    }

    private GuardedChannel openChannel(Session session) throws JSchException {
        ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
        ChannelGuard guard = new ChannelGuard(channel, inactivityTimeoutMillis);
        try {
            channel.connect(Math.min(CONNECT_TIMEOUT_MS, inactivityTimeoutMillis));
            if (guard.expired || guard.cancelled) throw new ChannelDeadlineException("SFTP channel opening stopped");
            return new GuardedChannel(channel, guard);
        } catch (JSchException | RuntimeException error) {
            guard.close();
            channel.disconnect();
            if (guard.expired || guard.cancelled) throw new ChannelDeadlineException("SFTP channel opening stopped", error);
            throw error;
        }
    }

    private static ScheduledThreadPoolExecutor watchdogExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2, action -> {
            Thread thread = new Thread(action, "sftp-channel-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static final class GuardedChannel {
        final ChannelSftp channel;
        final ChannelGuard guard;
        GuardedChannel(ChannelSftp channel, ChannelGuard guard) { this.channel = channel; this.guard = guard; }
    }

    private static final class ChannelDeadlineException extends JSchException {
        ChannelDeadlineException(String message) { super(message); }
        ChannelDeadlineException(String message, Throwable cause) { super(message, cause); }
    }

    /** SSH keepalives do not prove that the SFTP subsystem is answering requests. */
    private static final class ChannelGuard {
        private final Channel channel;
        private final Thread worker = Thread.currentThread();
        private final long idleNanos;
        private volatile long deadline;
        private volatile boolean closed;
        volatile boolean expired;
        volatile boolean cancelled;
        private final ScheduledFuture<?> watch;

        ChannelGuard(Channel channel, int timeoutMillis) {
            this.channel = channel;
            idleNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            deadline = System.nanoTime() + idleNanos;
            long interval = Math.min(50, timeoutMillis);
            watch = CHANNEL_WATCHDOG.scheduleWithFixedDelay(this::inspect, interval, interval, TimeUnit.MILLISECONDS);
        }

        private synchronized void inspect() {
            if (closed) return;
            cancelled = worker.isInterrupted();
            expired = System.nanoTime() >= deadline;
            if (cancelled || expired) {
                closed = true;
                channel.disconnect();
            }
        }

        synchronized void activity() throws SftpException {
            if (worker.isInterrupted()) cancelled = true;
            check();
            deadline = System.nanoTime() + idleNanos;
        }

        void check() throws SftpException {
            if (expired || cancelled) {
                IOException cause = cancelled ? new IOException("SFTP operation cancelled")
                    : new SocketTimeoutException("SFTP channel inactivity deadline exceeded");
                throw new SftpException(ChannelSftp.SSH_FX_FAILURE, cause.getMessage(), cause);
            }
        }

        synchronized void close() { closed = true; watch.cancel(false); }
    }

    private Session getSession(Credentials credentials) throws JSchException {
        synchronized (sessions) {
            Credentials key = credentials;
            Session cached = sessions.get(key);
            if (cached != null && cached.isConnected()) {
                return cached;
            }
            if (cached != null) {
                cached.disconnect();
                sessions.remove(key);
            }
            Long blockedUntil = authFailureUntil.get(key);
            if (blockedUntil != null && System.currentTimeMillis() < blockedUntil) {
                throw new JSchException("Auth fail (credentials recently rejected, not retrying)");
            }
            JSch jsch = new JSch();
            jsch.setKnownHosts(knownHostsFile.getAbsolutePath());
            Session session = jsch.getSession(credentials.username, credentials.host, credentials.port);
            session.setUserInfo(new TrustOnFirstUseUserInfo(credentials.password));
            session.setConfig("StrictHostKeyChecking", "ask");
            session.setConfig("PreferredAuthentications", "password,keyboard-interactive");
            session.setConfig("MaxAuthTries", "1");
            session.setServerAliveInterval(SERVER_ALIVE_INTERVAL_MS);
            session.setServerAliveCountMax(2);
            session.setTimeout(30000);
            // Without this JSch caps each channel pipe at 32 KiB, far below the pipelined reads.
            session.setConfig("max_input_buffer_size", Integer.toString(CHANNEL_INPUT_BUFFER));
            try {
                session.connect(CONNECT_TIMEOUT_MS);
            } catch (JSchException e) {
                if (isAuthFailure(e)) {
                    authFailureUntil.put(key, System.currentTimeMillis() + AUTH_FAILURE_COOLDOWN_MS);
                }
                throw e;
            }
            authFailureUntil.remove(key);
            sessions.put(key, session);
            return session;
        }
    }

    public static boolean isAuthFailure(Exception e) {
        String message = String.valueOf(e.getMessage());
        return e instanceof JSchException &&
            (message.contains("Auth fail") || message.contains("Auth cancel") || message.contains("Login trials exceeds"));
    }

    private void dropSession(Credentials credentials) {
        synchronized (sessions) {
            Session session = sessions.remove(credentials);
            idleReaders.remove(credentials);
            if (session != null) {
                session.disconnect();
            }
        }
    }

    private static String join(String parent, String name) {
        return parent.endsWith("/") ? parent + name : parent + "/" + name;
    }

    /** These JSch APIs expand globs; library paths always refer to one literal file or directory. */
    private static String literalPath(String path) {
        return path.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?");
    }

    /**
     * Accepts and remembers the host key the first time a server is seen, and rejects the
     * connection if a known host later presents a different key. The password is handed out
     * once per connection so a wrong password is never re-sent.
     */
    private static final class TrustOnFirstUseUserInfo implements UserInfo, UIKeyboardInteractive {

        private final String password;
        private boolean passwordUsed;

        TrustOnFirstUseUserInfo(String password) {
            this.password = password;
        }

        @Override
        public String getPassphrase() {
            return null;
        }

        @Override
        public String getPassword() {
            return password;
        }

        @Override
        public synchronized boolean promptPassword(String message) {
            if (password == null || passwordUsed) {
                return false;
            }
            passwordUsed = true;
            return true;
        }

        @Override
        public boolean promptPassphrase(String message) {
            return false;
        }

        @Override
        public boolean promptYesNo(String message) {
            return !message.contains("HOST IDENTIFICATION HAS CHANGED");
        }

        @Override
        public void showMessage(String message) {}

        @Override
        public synchronized String[] promptKeyboardInteractive(
            String destination,
            String name,
            String instruction,
            String[] prompt,
            boolean[] echo
        ) {
            if (password == null || passwordUsed) {
                return null;
            }
            passwordUsed = true;
            String[] answers = new String[prompt.length];
            Arrays.fill(answers, password);
            return answers;
        }
    }
}
