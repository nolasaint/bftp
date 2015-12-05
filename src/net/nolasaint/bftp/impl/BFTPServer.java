package net.nolasaint.bftp.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

import java.util.HashSet;
import java.util.Set;

/**
 * @TODO Documentation
 *
 * NOTE: Runs on top of TCP.
 *
 * @author  Evan Bailey
 * @version 1.0
 * @since   2015-30-11
 */
public class BFTPServer {

    /* LOGGING_MAXWIDTH indicates the maximum length of a log entry line without the prefix */
    private static final int LOGGING_MAXWIDTH = 80;

    /* LOGGING_PREFIX occurs before all logging entries */
    private static final String LOGGING_PREFIX = "[Server] ";

    /* LOGGING_PADDING is used to align multi-line log entries */
    private static final String LOGGING_PADDING = "         ";

    private final PrintWriter logstream;

    private boolean listen;
    private int port;
    private Set<ClientHandler> clientHandlers; // Todo - Every once in a while poll this and see if client closed
    private Object logLock = new Object();
    private ServerSocket ssocket;

    /**
     * Creates a bound, logging BFTP Server.
     *
     * @param   port        the port number on which to bind the server
     * @param   logstream   the OutputStream to log to
     *
     * @throws  IllegalArgumentException if the port parameter is outside the specified range of
     *          valid port values, which is between 0 and 65535, inclusive.
     *
     * @throws  IOException if an I/O error occurs when opening the socket.
     */
    public BFTPServer(int port, OutputStream logstream) throws IOException {
        if (null == logstream) {
            this.logstream = null;
        }
        else {
            this.logstream = new PrintWriter(logstream);
        }

        // TODO Potentially log initial message (GREETING) ?

        ssocket = new ServerSocket(port);
        this.port = port;
        listen = false;

        // Avoid instantiating this if ServerSocket creation causes IOException
        clientHandlers = new HashSet<>();

        log("BFTP server created and bound to port " + port);
    }

    /**
     * Creates a bound, non-logging BFTP Server.
     *
     * @param   port    the port number on which to bind the server
     *
     * @throws  IllegalArgumentException if the port parameter is outside the specified range of
     *          valid port values, which is between 0 and 65535, inclusive.
     *
     * @throws  IOException if an I/O error occurs when opening the socket.
     */
    public BFTPServer(int port) throws IOException {
        this(port, null);
    }

    /* PUBLIC MEMBERS */

    /**
     * Allows server to accept and handle incoming clients.
     *
     * This method is synchronized by necessity, to prevent calling run() on an already-running
     * server.
     *
     * @throws  IOException if an I/O error occurs when waiting for a connection.
     */
    public synchronized void run() throws IOException {
        listen = true;
        log("Handling incoming connections");

        while (listen) {
            try {
                ClientHandler clientHandler;
                Socket csocket = ssocket.accept(); // blocks until a connection is available
                String clientID = csocket.getInetAddress().getHostAddress() + ":" + csocket.getPort();

                log("Accepted connection from client at " + clientID);

                clientHandler = new ClientHandler(csocket, clientID);
                clientHandlers.add(clientHandler);

                new Thread(clientHandler).start();
            }
            catch (SocketException se) {
                // Socket times out
            }
        }

        // TODO Either here or in close(), call close() on clienthandlers
        log("No longer handling incoming connections");
    }

    /**
     * Stops the server from accepting new incoming connections, and gracefully closes any client
     * handlers running.
     *
     * Server may take up to BFTPServer.ACCEPT_TIMEOUT ms to fully shutdown.
     */
    public void shutdown() throws IOException {
        ssocket.close();
        listen = false;

        // TODO handle close() in here? would add delay yes?
        // Gracefully close client handlers
        for (ClientHandler handler : clientHandlers) {
            handler.stop();
        }
    }

    /* PROTECTED MEMBERS */

    /*
     * TODO Documentation
     *
     * Protected s.t. subclasses may use it
     */
    protected class ClientHandler implements Runnable {

        private final String clientID;

        private boolean shouldClose;
        private InputStream input;
        private OutputStream output;
        private Socket csocket;

        /**
         * Creates a new ClientHandler for the provided socket.
         *
         * @param   csocket     the socket connected to the client to be handled
         * @param   clientID    identification string for the client
         */
        public ClientHandler(Socket csocket, String clientID) {
            this.csocket = csocket;
            this.clientID = "CH_" + clientID + "> ";
            shouldClose = false;

            log(this.clientID + "Client handler created");
        }

        // TODO Priorityclients? Avoids force-closing the handler

        /**
         * Notifies the client handler to close the connection gracefully.
         *
         * A graceful close will only terminate the connection when the handler is not currently
         * processing a command from the client.
         */
        public synchronized void stop() {
            shouldClose = true;
            // TODO Mid-Put case? Timeout after a while
            log("Will close after handling current command");
        }

        @Override
        public void run() {
            log("Handling client connection");

            try {
                input = csocket.getInputStream();
                output = csocket.getOutputStream();
            }
            catch (IOException ioe) {
                shouldClose = true;

                log ("Failed to get input/output streams");
            }

            while (!shouldClose) {
                // TODO internal state machine

                // TODO simulate command handling
                try { Thread.sleep(10000); }
                catch (InterruptedException iex) {}

                // Close if we see the socket has closed
                shouldClose |= csocket.isClosed();
            }

            log("Closing connection with client");
            // TODO send(FIN)
        }

        /*
         * Helper method to log client handler output.
         */
        private void log(String entry) {
            BFTPServer.this.log(clientID + entry);
        }

    }

    /* PRIVATE MEMBERS */

    /*
     * Helper method to log server output to the OutputStream logstream.
     * If logstream is null, nothing is done.
     */
    private void log(String entry) {
        if (null != logstream && null != entry) {
            int offset = 0, length = 0;
            String partialEntry = LOGGING_PREFIX;

            // Ensure newlines will be aligned
            entry = entry.replaceAll("\n", "\n" + LOGGING_PADDING);

            do {
                // Determine length of partial entry
                length = Math.min(LOGGING_MAXWIDTH, entry.length() - offset);
                partialEntry = partialEntry.concat(entry.substring(offset, offset + length));
                logstream.println(partialEntry);

                // Pad next partial entry
                partialEntry = LOGGING_PADDING;
                offset += length;
            }
            while (length >= LOGGING_MAXWIDTH);

            logstream.flush();
        }
    }

}
