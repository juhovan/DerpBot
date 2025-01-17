package fi.derpnet.derpbot.connector;

import fi.derpnet.derpbot.bean.RawMessage;
import fi.derpnet.derpbot.controller.MainController;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.Thread.UncaughtExceptionHandler;
import java.net.Socket;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import javax.net.ssl.SSLSocketFactory;
import org.apache.log4j.Logger;

public class IrcConnector {

    public static final int PING_INTERVAL_MS = 60_000;
    public static final int PING_TIMEOUT_MS = 30_000;
    public static final int WATCHER_POLLRATE_MS = 5_000;

    public final String networkName;
    public final String hostname;
    public final int port;
    public final boolean ssl;
    public final String user;
    public final String realname;
    public final int ratelimit;

    private static final Logger LOG = Logger.getLogger(IrcConnector.class);

    private final MainController controller;
    private final Timer connectionWatcherTimer;
    private String nick;
    private Socket socket;
    private ReceiverThread receiverThread;
    private SenderThread senderThread;
    private ConnectionWatcher connectionWatcher;
    private UncaughtExceptionHandler uncaughtExceptionHandler;
    private List<String> channels;
    private List<String> quieterChannels;

    public IrcConnector(String networkName, String hostname, int port, boolean ssl, String user, String realname, String nick, int ratelimit, MainController controller) {
        this.networkName = networkName;
        this.hostname = hostname;
        this.port = port;
        this.ssl = ssl;
        this.user = user;
        this.realname = realname;
        this.nick = nick;
        this.ratelimit = ratelimit;
        this.controller = controller;
        connectionWatcherTimer = new Timer();
    }

    public void connect() throws IOException {
        boolean retry;
        BufferedWriter writer = null;
        BufferedReader reader = null;

        do {
            try {
                retry = false;
                LOG.info("Connecting to " + networkName + " on " + hostname + " port " + port + (ssl ? " using SSL" : " without SSL"));
                if (ssl) {
                    socket = SSLSocketFactory.getDefault().createSocket(hostname, port);
                } else {
                    socket = new Socket(hostname, port);
                }
                writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                writer.write("NICK " + nick + "\r\n");
                writer.write("USER " + user + " 8 * :" + realname + "\r\n");
                writer.flush();

                String line;
                String pendingNick = nick;
                while ((line = reader.readLine()) != null) {
                    System.out.println(Thread.currentThread().getId() + " << " + line);
                    if (line.contains("004")) {
                        // We are now logged in.
                        break;
                    } else if (line.contains("433")) {
                        //TODO prettier alt nick generation
                        pendingNick = pendingNick + "_";
                        writer.write("NICK " + pendingNick + "\r\n");
                        writer.flush();
                    } else if (line.toUpperCase().startsWith("PING ")) {
                        writer.write("PONG :" + line.substring(6) + "\r\n");
                        writer.flush();
                    }
                }

                if (!nick.equals(pendingNick)) {
                    nick = pendingNick;
                }
            } catch (IOException ex) {
                LOG.error("Got an IOExcpetion while trying to connect, trying again after a while", ex);
                try {
                    Thread.sleep(PING_TIMEOUT_MS);
                } catch (InterruptedException e) {
                }
                retry = true;
            }
        } while (retry);

        connectionWatcher = new ConnectionWatcher();
        uncaughtExceptionHandler = new ThreadUncaughtExceptionHandler();
        receiverThread = new ReceiverThread(reader);
        receiverThread.setUncaughtExceptionHandler(uncaughtExceptionHandler);
        receiverThread.start();
        senderThread = new SenderThread(writer);
        senderThread.setUncaughtExceptionHandler(uncaughtExceptionHandler);
        senderThread.start();

        connectionWatcherTimer.schedule(connectionWatcher, 10000, PING_INTERVAL_MS);
    }

    public void disconnect() {
        connectionWatcher.cancel();
        connectionWatcherTimer.purge();
        try {
            socket.close();
            LOG.info("Disconnected from " + hostname);
        } catch (IOException ex) {
            LOG.error("Failed to disconnect from " + hostname + ", this connector may be in an inconsistent state!", ex);
        }
    }

    public void send(RawMessage msg) {
        senderThread.messageQueue.add(msg);
    }

    public void setChannels(List<String> channels, boolean join) {
        this.channels = channels;
        if (join) {
            channels.forEach(channel -> {
                LOG.info("Joining channel " + channel + " on " + networkName);
                senderThread.messageQueue.add(new RawMessage(null, "JOIN", channel));
            });
        }
    }

    public List<String> getQuieterChannels() {
        return quieterChannels;
    }

    public void setQuieterChannels(List<String> quieterChannels) {
        this.quieterChannels = quieterChannels;
    }

    private void handleConnectionLoss() {
        LOG.warn("Lost connection to " + hostname + ", reconnecting...");
        disconnect();
        try {
            connect();
            channels.forEach(channel -> {
                LOG.info("Joining channel " + channel + " on " + networkName + " after a reconnect");
                senderThread.messageQueue.add(new RawMessage(null, "JOIN", channel));
            });
        } catch (IOException ex) {
            LOG.error("Failed to reconnect to " + hostname + ", this connector may be in an inconsistent state!", ex);
        }
    }

    private class ReceiverThread extends Thread {

        private final BufferedReader reader;

        public ReceiverThread(BufferedReader reader) {
            this.reader = reader;
        }

        @Override
        public void run() {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(Thread.currentThread().getId() + " < " + line);
                    connectionWatcher.gotMessage();
                    RawMessage msg = new RawMessage(line);
                    if (msg.command.equals("PING")) {
                        // We must respond to PINGs to avoid being disconnected.
                        // The response is written directly to avoid delay due to outbound message queue
                        senderThread.writer.write("PONG :" + line.substring(6) + "\r\n");
                        senderThread.writer.flush();
                    }
                    List<RawMessage> responses = controller.handleIncoming(IrcConnector.this, msg);
                    if (responses != null) {
                        senderThread.messageQueue.addAll(responses);
                    }
                }
                LOG.warn("Got EOF from " + hostname + ", socket closed? Reconnecting...");
                handleConnectionLoss();
            } catch (IOException ex) {
                LOG.error("Writes not working for network " + networkName + ". Connection lost?", ex);
            }
        }
    }

    private class SenderThread extends Thread {

        private final BufferedWriter writer;
        private final BlockingQueue<RawMessage> messageQueue;

        public SenderThread(BufferedWriter writer) {
            this.writer = writer;
            messageQueue = new LinkedBlockingQueue<>();
        }

        @Override
        public void run() {
            while (!interrupted()) {
                try {
                    RawMessage nextMsg = messageQueue.take();
                    writer.write(nextMsg.toString());
                    writer.write("\r\n");
                    writer.flush();
                    System.out.println(Thread.currentThread().getId() + " > " + nextMsg.toString());
                    sleep(ratelimit);
                } catch (InterruptedException ex) {
                    break;
                } catch (IOException ex) {
                    LOG.error("Writes not working for network " + networkName + ". Connection lost?", ex);
                }
            }
        }
    }

    private class ConnectionWatcher extends TimerTask {

        private long lastMessage;

        public ConnectionWatcher() {
            lastMessage = System.currentTimeMillis();
        }

        @Override
        public void run() {
            try {
                if (System.currentTimeMillis() - lastMessage < PING_TIMEOUT_MS) {
                    return;
                }
                long lastMessageReceivedBeforeSending = lastMessage;
                senderThread.writer.write("PING :" + System.currentTimeMillis() + "\r\n");
                senderThread.writer.flush();
                long timeSent = System.currentTimeMillis();

                //No need to wait; we got a message already when sending our PING
                if (lastMessageReceivedBeforeSending != lastMessage) {
                    return;
                }

                //Wait for message; poll if result came and wait for maximum of ping timeout
                while (lastMessage < timeSent && System.currentTimeMillis() - timeSent <= PING_TIMEOUT_MS) {
                    Thread.sleep(WATCHER_POLLRATE_MS);
                }
                //No message? --> connection lost
                if (System.currentTimeMillis() - lastMessage > PING_TIMEOUT_MS) {
                    handleConnectionLoss();
                }
            } catch (IOException ex) {
                handleConnectionLoss();
            } catch (InterruptedException ex) {
            }
        }

        public void gotMessage() {
            lastMessage = System.currentTimeMillis();
        }
    }

    private class ThreadUncaughtExceptionHandler implements UncaughtExceptionHandler {

        @Override
        public void uncaughtException(Thread thread, Throwable e) {
            if (thread instanceof SenderThread) {
                LOG.error("SenderThread in network " + networkName + " exited with uncaught exception", e);
            } else if (thread instanceof ReceiverThread) {
                LOG.error("ReceiverThread in network " + networkName + " exited with uncaught exception", e);
            } else {
                LOG.error("Unknown thread in network " + networkName + " exited with uncaught exception(?)", e);
            }
        }

    }
}
