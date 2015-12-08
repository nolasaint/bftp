package net.nolasaint.bftp.impl;

import net.nolasaint.bftp.BFTP;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;

import java.util.HashSet;
import java.util.Set;

/**
 * TODO: Documentation
 *
 * TODO: Extensibility (what vars need protected vs which can be private?)
 *
 * TODO: Allow run() to be called after shutdown()
 *
 * NOTE: Runs on top of TCP.
 *
 * @author  Evan Bailey
 * @version 1.0
 * @since   2015-30-11
 */
public class BFTPServer {

    /* DEFAULT_ENCODING specifies the server's default String encoding format */
    private static final String DEFAULT_ENCODING = "UTF-8";

    /* LOGGING_MAX_WIDTH indicates the maximum length of a log entry line without the prefix */
    private static final int LOGGING_MAX_WIDTH = 120;

    /* LOGGING_PADDING is used to align multi-line log entries */
    private static final String LOGGING_PADDING = "         ";

    /* LOGGING_PREFIX occurs before all logging entries */
    private static final String LOGGING_PREFIX = "[Server] ";

    /* ROOT_DIRECTORY is the directory from which the server may find target files for BFTP */
    private static final String ROOT_DIRECTORY = "public/";

    private final PrintWriter logstream;

    private boolean listen;
    private ServerSocket ssocket;
    private Set<ClientHandler> clientHandlers;

    /**
     * Creates a bound, logging BFTPServer.
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
     * Creates a bound, non-logging BFTPServer.
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
                // Server socket closed, generally from shutdown()
            }
        }

        log("No longer handling incoming connections");
    }

    /**
     * Stops the server from accepting new incoming connections, and gracefully closes any client
     * handlers running.
     *
     * @throws  IOException if one is encountered while trying to close server socket, or if server
     *          socket is already closed.
     */
    public void shutdown() throws IOException {
        ssocket.close();
        listen = false;

        // Gracefully close client handlers
        clientHandlers.forEach(ClientHandler::stop);
    }

    /* PROTECTED MEMBERS */

    /* PRIVATE MEMBERS */

    /**
     * Logs output to the BFTPServer instance's logging PrintWriter, if it exists.
     *
     * If the logging PrintWriter is null, this method does nothing.
     *
     * @param   entry   - String that will be logged
     */
    private void log(String entry) {
        if (null != logstream) {
            String partialEntry = LOGGING_PREFIX;
            String entryLines[] = entry.split("\n");

            for (String line : entryLines) {
                int length, offset = 0;
                do {
                    // Determine length of partial entry
                    length = Math.min(LOGGING_MAX_WIDTH, line.length() - offset);
                    partialEntry = partialEntry.concat(line.substring(offset, offset + length));
                    logstream.println(partialEntry);

                    // Pad next partial entry
                    partialEntry = LOGGING_PADDING;
                    offset += length;
                }
                while (length >= LOGGING_MAX_WIDTH);
            }

            logstream.flush();
        }
    }

    /**
     * TODO Documentation
     */
    private class ClientHandler implements Runnable {

        private static final String
                FILE_NOT_FOUND_RESPONSE      = "File not found";

        private static final String
                FILE_READ_ERROR_RESPONSE     = "Encountered error while reading file";

        private static final String
                FILE_TOO_LARGE_RESPONSE      = "Requested file is too large (> ~2GiB)";

        private static final String
                UNSUPPORTED_COMMAND_RESPONSE = "Unsupported command";

        private final String clientID, padding;

        private boolean isFin, shouldClose, stopped;
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
            stopped = false;

            // Generate padding for logger
            padding = this.clientID.replaceAll(".", " ");

            log(this.clientID + "Client handler created");
        }

        /**
         * Returns whether this ClientHandler has finished running.
         *
         * This method is deprecated since ClientHandler removes itself from the parent BFTPServer
         * instance's set of ClientHandlers once it is done running.
         *
         * @return  TRUE if the ClientHandler is no longer running, else FALSE.
         */
        @Deprecated
        public boolean isStopped() {
            return stopped;
        }

        @Override
        public void run() {
            log("Handling client connection");

            ByteBuffer buffer; // Used to construct closing FIN packet

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

            buffer = ByteBuffer.allocate(BFTP.HEADER_LENGTH);

            buffer.putInt(0); // no content
            buffer.put(BFTP.FIN);

            if (!csocket.isClosed()) {
                try {
                    output.write(buffer.array());
                    output.flush();

                    // Close resources
                    input.close();
                    output.close();
                    csocket.close();
                }
                catch (IOException ioe) {
                    log("Encountered IOException while cleaning up connection");
                }
            }
            else {
                log("Client socket already closed, cannot send FIN");
            }

            stopped = true;
            clientHandlers.remove(this);
        }

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

        /**
         * Helper method to convert the given byte array to a String.
         *
         * Attempts to use UTF-8 encoding, but will default to the native machine encoding if UTF-8
         * is not supported.
         *
         * This method is essentially a wrapper around an UnsupportedEncodingException.
         *
         * @param   bytes   - byte array to convert to a String
         *
         * @return  a String formed from the provided bytes.
         */
        private String bytesToString(byte bytes[]) {
            String string;

            try {
                string = new String(bytes, DEFAULT_ENCODING);
            }
            catch (UnsupportedEncodingException uee) {
                // Should never be reached
                throw new AssertionError(DEFAULT_ENCODING + " encoding not supported");
            }

            return string;
        }

        /**
         * Helper method to safely check if a file with the specified path exists.
         *
         * @param   path    - the path at which to check if a file exists
         * @return  TRUE if the file exists, FALSE otherwise.
         */
        private boolean fileExists(String path) {
            boolean exists;

            try {
                exists = Files.exists(Paths.get(path));
            }
            catch (InvalidPathException ipe) {
                exists = false;
            }

            return exists;
        }

        /**
         * Helper method to handle a GET request.
         *
         * @param   content - bytes of the content field
         *
         * @throws  IOException if one is encountered while writing to the socket.
         */
        private void handleGet(byte content[]) throws IOException {
            byte responseOpcode, responseContent[];
            ByteBuffer buffer;
            String path = bytesToString(content);

            log("Received GET request from client:\n> GET " + path);

            // Only look for file in specific public directory
            if (fileExists(ROOT_DIRECTORY + path)) {
                // Don't throw IOException from file reads
                try {
                    responseOpcode = BFTP.GET | BFTP.RSP;
                    responseContent = Files.readAllBytes(Paths.get(ROOT_DIRECTORY + path));

                    log("Sending requested file to client");
                }
                catch (OutOfMemoryError ome) {
                    responseOpcode = BFTP.GET | BFTP.RSP;
                    responseContent = stringToBytes(FILE_TOO_LARGE_RESPONSE);

                    log("Requested file is too large");
                }
                catch (IOException ie) {
                    responseOpcode = BFTP.GET | BFTP.ERR;
                    responseContent = stringToBytes(FILE_READ_ERROR_RESPONSE);

                    log("Encounter IOException while reading from file");
                }
            }
            else {
                responseOpcode = BFTP.GET | BFTP.ERR;
                responseContent = stringToBytes(FILE_NOT_FOUND_RESPONSE);

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
         *
         * The clientID String is added as a prefix to the provided entry before logging.
         *
         * @param   entry   - String that will be logged
         */
        private void log(String entry) {
            // Ensure newlines will be aligned
            entry = entry.replaceAll("\n", "\n" + padding);

            BFTPServer.this.log(clientID + entry);
        }

        /**
         * Helper method to convert the given String to a byte array.
         *
         * Attempts to use UTF-8 encoding, but will default to the native machine encoding if UTF-8
         * is not supported.
         *
         * This method is essentially a wrapper around an UnsupportedEncodingException.
         *
         * @param   string  - string to get bytes from
         *
         * @return  the byte array representation of the provided string.
         */
        private byte[] stringToBytes(String string) {
            byte stringBytes[];

            try {
                stringBytes = string.getBytes(DEFAULT_ENCODING);
            }
            catch (UnsupportedEncodingException uee) {
                // Should never be reached
                throw new AssertionError(DEFAULT_ENCODING + " encoding not supported");
            }

            return stringBytes;
        }

    }

}
