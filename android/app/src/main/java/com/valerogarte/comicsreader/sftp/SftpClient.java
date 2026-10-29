package com.valerogarte.comicsreader.sftp;

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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Plain JSch wrapper (no Android dependencies) that keeps one SSH session per set of credentials. */
public final class SftpClient {

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int MAX_RANGE_SIZE = 4 * 1024 * 1024;
    private static final int MAX_SFTP_PACKET = 256 * 1024;
    private static final int SERVER_ALIVE_INTERVAL_MS = 15000;
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

        String cacheKey() {
            return username + "@" + host + ":" + port + "#" + (password == null ? 0 : password.hashCode());
        }
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
    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<String, Long> authFailureUntil = new HashMap<>();

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
            String path = shelfPath(channel, root, relativePath);
            Vector<ChannelSftp.LsEntry> rawEntries = channel.ls(literalPath(path));
            List<Entry> entries = new ArrayList<>();
            for (ChannelSftp.LsEntry rawEntry : rawEntries) {
                String name = rawEntry.getFilename();
                if (!isShelfEntryName(name)) continue;
                SftpATTRS attrs = rawEntry.getAttrs();
                if (attrs.isLink()) {
                    try {
                        String target = channel.realpath(join(path, name));
                        if (!insideRoot(root, target)) continue;
                        attrs = channel.stat(literalPath(target));
                    } catch (SftpException ignored) {
                        continue;
                    }
                }
                if (!attrs.isDir() && !attrs.isReg()) continue;
                entries.add(new Entry(name, attrs.getSize(), attrs.isDir(), attrs.getMTime() * 1000L));
            }
            return entries;
        });
    }

    /** Reads only the requested bytes without creating a temporary comic file. */
    public byte[] readShelfRange(Credentials credentials, String rootPath, String relativePath, long offset, int length)
        throws JSchException, SftpException {
        if (offset < 0 || offset > 9007199254740991L || length < 0 || length > MAX_RANGE_SIZE
            || offset > 9007199254740991L - length) {
            throw new IllegalArgumentException("Invalid SFTP byte range");
        }
        return withChannel(credentials, (channel, guard) -> {
            String root = channel.realpath(normalizeShelfPath(rootPath));
            String path = shelfPath(channel, root, relativePath);
            SftpATTRS attrs = channel.stat(literalPath(path));
            if (!attrs.isReg()) throw new IllegalArgumentException("Expected an SFTP file");
            int count = (int) Math.min(length, Math.max(0, attrs.getSize() - offset));
            if (count == 0) return new byte[0];
            try {
                return readExactRange(channel.getSession(), path, offset, count, rangeTimeoutMillis);
            } catch (IOException e) {
                throw new SftpException(ChannelSftp.SSH_FX_FAILURE, "Could not read SFTP byte range", e);
            }
        });
    }

    /** JSch's file InputStream prefetches 32 KiB even for read(1); this small v3 exchange does not. */
    private static byte[] readExactRange(Session session, String path, long offset, int count, int timeoutMillis)
        throws JSchException, SftpException, IOException {
        long deadline = System.nanoTime() + timeoutMillis * 1000000L;
        ChannelSubsystem channel = (ChannelSubsystem) session.openChannel("subsystem");
        channel.setSubsystem("sftp");
        channel.setWantReply(true);
        try {
            DataInputStream input = new DataInputStream(channel.getInputStream());
            DataOutputStream output = new DataOutputStream(channel.getOutputStream());
            channel.connect(Math.min(CONNECT_TIMEOUT_MS, timeoutMillis));
            sendSftpPacket(output, 1, 3, new byte[0]); // INIT, client version 3.
            DataInputStream version = receiveSftpPacket(input, channel, deadline);
            if (version.readUnsignedByte() != 2 || version.readInt() != 3) throw new IOException("Unsupported SFTP version");

            byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
            if (pathBytes.length > 16384) throw new IOException("SFTP path too long");
            ByteArrayOutputStream openBytes = new ByteArrayOutputStream();
            DataOutputStream open = new DataOutputStream(openBytes);
            writeSftpString(open, pathBytes);
            open.writeInt(1); // SSH_FXF_READ only: no create, append or write flags.
            open.writeInt(0); // Empty attributes.
            int requestId = 1;
            sendSftpPacket(output, 3, requestId, openBytes.toByteArray()); // OPEN.
            DataInputStream opened = receiveSftpResponse(input, requestId, 102, false, channel, deadline);
            byte[] handle = readSftpString(opened, 256);
            ByteArrayOutputStream result = new ByteArrayOutputStream(count);
            while (result.size() < count) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("SFTP range cancelled");
                int requested = Math.min(32768, count - result.size());
                ByteArrayOutputStream readBytes = new ByteArrayOutputStream();
                DataOutputStream read = new DataOutputStream(readBytes);
                writeSftpString(read, handle);
                read.writeLong(offset + result.size());
                read.writeInt(requested);
                sendSftpPacket(output, 5, ++requestId, readBytes.toByteArray()); // READ, exact remaining length.
                DataInputStream data = receiveSftpResponse(input, requestId, 103, true, channel, deadline);
                if (data == null) break; // Remote file became shorter between STAT and READ.
                byte[] bytes = readSftpString(data, requested);
                if (bytes.length == 0 || data.available() > 1) throw new IOException("Invalid SFTP data response");
                result.write(bytes);
            }
            ByteArrayOutputStream closeBytes = new ByteArrayOutputStream();
            writeSftpString(new DataOutputStream(closeBytes), handle);
            sendSftpPacket(output, 4, ++requestId, closeBytes.toByteArray()); // CLOSE and wait for acknowledgement.
            receiveSftpResponse(input, requestId, 101, false, channel, deadline);
            return result.toByteArray();
        } finally { channel.disconnect(); }
    }

    private static void sendSftpPacket(DataOutputStream output, int type, int id, byte[] payload) throws IOException {
        if (payload.length > MAX_SFTP_PACKET - 5) throw new IOException("SFTP request too large");
        output.writeInt(payload.length + 5);
        output.writeByte(type);
        output.writeInt(id);
        output.write(payload);
        output.flush();
    }

    private static DataInputStream receiveSftpPacket(DataInputStream input, ChannelSubsystem channel, long deadline) throws IOException {
        int length = new DataInputStream(new ByteArrayInputStream(readSftpBytes(input, 4, channel, deadline))).readInt();
        if (length < 5 || length > MAX_SFTP_PACKET) throw new IOException("Invalid SFTP packet length");
        return new DataInputStream(new ByteArrayInputStream(readSftpBytes(input, length, channel, deadline)));
    }

    private static byte[] readSftpBytes(DataInputStream input, int length, ChannelSubsystem channel, long deadline) throws IOException {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("SFTP range cancelled");
            if (System.nanoTime() >= deadline) throw new SocketTimeoutException("SFTP range deadline exceeded");
            int available = input.available();
            if (available == 0) {
                if (channel.isClosed() || channel.isEOF()) throw new EOFException("SFTP range channel closed");
                try { Thread.sleep(2); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("SFTP range cancelled", error); }
                continue;
            }
            int read = input.read(result, offset, Math.min(available, length - offset));
            if (read < 0) throw new EOFException("Incomplete SFTP response");
            offset += read;
        }
        return result;
    }

    private static DataInputStream receiveSftpResponse(DataInputStream input, int requestId, int expectedType, boolean allowEof,
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
                String path = shelfPath(channel, root, relativePath);
                SftpATTRS before = channel.stat(literalPath(path));
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
        private final ChannelSftp channel;
        private final Thread worker = Thread.currentThread();
        private final long idleNanos;
        private volatile long deadline;
        private volatile boolean closed;
        volatile boolean expired;
        volatile boolean cancelled;
        private final ScheduledFuture<?> watch;

        ChannelGuard(ChannelSftp channel, int timeoutMillis) {
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
            String key = credentials.cacheKey();
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
            Session session = sessions.remove(credentials.cacheKey());
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
