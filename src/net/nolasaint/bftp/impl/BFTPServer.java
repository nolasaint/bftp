package net.nolasaint.bftp.impl;

import net.nolasaint.bftp.BFTP;

import java.io.*;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

/**
 * TODO Documentation
 *
 * NOTE: Runs on top of TCP.
 *
 * @author  Evan Bailey
 * @version 1.0
 * @since   2015-30-11
 */
public class BFTPServer {

    /* LOGGING_MAX_WIDTH indicates the maximum length of a log entry line without the prefix */
    private static final int LOGGING_MAX_WIDTH = 80;

    /* LOGGING_PREFIX occurs before all logging entries */
    private static final String LOGGING_PREFIX = "[Server] ";

    /* LOGGING_PADDING is used to align multi-line log entries */
    private static final String LOGGING_PADDING = "         ";

    private final PrintWriter logstream;

    private boolean listen;
    private Set<ClientHandler> clientHandlers; // Todo - Every once in a while poll this and see if client closed
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

        ssocket = new ServerSocket(port);
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

        // Gracefully close client handlers
        clientHandlers.forEach(ClientHandler::stop);
    }

    /* PROTECTED MEMBERS */

    /*
     * TODO Documentation
     *
     * Protected s.t. subclasses may use it
     */
    protected class ClientHandler implements Runnable {

        private static final String
                FILE_NOT_FOUND_RESPONSE      = "File not found";

        private static final String
                FILE_READ_ERROR_RESPONSE     = "Encountered error while reading file";

        private static final String
                FILE_TOO_LARGE_RESPONSE     = "Requested file is too large (> ~2GiB)";

        private static final String
                UNSUPPORTED_COMMAND_RESPONSE = "Unsupported command";

        private final String clientID;

        private boolean isFin, shouldClose;
        private DataInputStream input;
        private DataOutputStream output;
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
        public void stop() {
            shouldClose = true;

            log("Will close after handling current command");
        }

        @Override
        public void run() {
            log("Handling client connection");

            // Try to create I/O streams
            shouldClose = !initializeIOStreams();
            isFin = false;

            while (!shouldClose) {
                byte content[], opcode;
                int csize;

                try {
                    // [0,4] ~ csize, [5,6] ~ opcode
                    csize = input.readInt();
                    opcode = input.readByte();

                    content = new byte[csize];
                    for (int i = 0; i < csize; i++) {
                        content[i] = input.readByte();
                    }

                    // Determine if FIN bit is set
                    isFin = ((opcode & BFTP.FIN) != 0);

                    switch (opcode) {
                        case BFTP.GET:
                            handleGet(content);
                            break;

                        case BFTP.PUT:
                            // TODO: implement
                            break;

                        case BFTP.FIN:
                            shouldClose = true;
                            break;

                        default:
                            handleUnsupported();

                            // Do not trust this client
                            shouldClose = true;
                            break;
                    }

                    log("Sent response to client");
                }
                catch (IOException ioe) {
                    // TODO: Handle differing types of exceptions
                    log("Encountered IOException while reading from client socket");

                    shouldClose = true;
                }

                if (isFin) {
                    log("FIN bit was set, closing connection");
                }

                // Close if we see the socket has closed
                shouldClose |= (isFin || csocket.isClosed());
            }

            log("Closing connection with client");

            // TODO: send(FIN)

            clientHandlers.remove(this);
        }

        /**
         * Helper method to handle a GET request.
         *
         * @param   content - bytes of the content field
         *
         * @throws  IOException if one is encountered while writing to the socket.
         */
        private void handleGet(byte content[]) throws IOException {
            log("Received GET request from client");

            byte responseOpcode, responseContent[];
            ByteBuffer buffer;
            String path = new String(content); // TODO: specify UTF-8?

            // Check if file exists
            if (Files.exists(Paths.get(path))) { // TODO: catch IllegalPathException
                // Don't throw IOException from file reads
                try {
                    responseOpcode = BFTP.GET | BFTP.RSP;
                    responseContent = Files.readAllBytes(Paths.get(path));

                    log("Sending requested file to client");
                }
                catch (OutOfMemoryError ome) {
                    responseOpcode = BFTP.GET | BFTP.RSP;
                    responseContent = FILE_TOO_LARGE_RESPONSE.getBytes(); // TODO: specify UTF-8?

                    log("Requested file is too large");
                }
                catch (IOException ie) {
                    responseOpcode = BFTP.GET | BFTP.ERR;
                    responseContent = FILE_READ_ERROR_RESPONSE.getBytes(); // TODO: specify UTF-8?

                    log("Encounter IOException while reading from file");
                }
            }
            else {
                responseOpcode = BFTP.GET | BFTP.ERR;
                responseContent = FILE_NOT_FOUND_RESPONSE.getBytes(); // TODO: specify UTF-8?

                log("Requested file was not found");
            }

            buffer = ByteBuffer.allocate(BFTP.HEADER_LENGTH + responseContent.length);

            buffer.putInt(responseContent.length);
            buffer.put(responseOpcode);
            buffer.put(responseContent);

            output.write(buffer.array());
        }

        /**
         * Helper method to handle an unsupported message type.
         *
         * @throws  IOException if one is encountered while writing to the socket.
         */
        private void handleUnsupported() throws IOException {
            log("Received unsupported command / message from client");

            byte responseContent[] = UNSUPPORTED_COMMAND_RESPONSE.getBytes();
            int length = BFTP.HEADER_LENGTH + responseContent.length;
            ByteBuffer buffer = ByteBuffer.allocate(length);

            // Build response
            buffer.putInt(responseContent.length);
            buffer.put(BFTP.ERR);
            buffer.put(responseContent);

            output.write(buffer.array());
        }

        /**
         * Helper method to create the input and output streams
         *
         * @return  TRUE if the I/O streams were created, else FALSE.
         */
        private boolean initializeIOStreams() {
            boolean successful;

            try {
                input = new DataInputStream(csocket.getInputStream());
                output = new DataOutputStream(csocket.getOutputStream());

                successful = true;
            }
            catch (IOException ioe) {
                successful = false;

                log ("Failed to get input/output streams");
            }

            return successful;
        }

        /**
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
                length = Math.min(LOGGING_MAX_WIDTH, entry.length() - offset);
                partialEntry = partialEntry.concat(entry.substring(offset, offset + length));
                logstream.println(partialEntry);

                // Pad next partial entry
                partialEntry = LOGGING_PADDING;
                offset += length;
            }
            while (length >= LOGGING_MAX_WIDTH);

            logstream.flush();
        }
    }

}
