package net.nolasaint.bftp.test;

import net.nolasaint.bftp.BFTP;
import net.nolasaint.bftp.impl.BFTPServer;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.BindException;
import java.net.Socket;
import java.nio.ByteBuffer;

/**
 * Test cases for BFTPServer.
 *
 * Created: 2015-12-05
 * @author  Evan
 */
public class BFTPServerTests {

    public static void testGetNonexistent() {
        int port = 0xFADE;
        String path = "public/fake/README.md";

        try {
            BFTPServer server   = new BFTPServer(port, System.out);
            Runnable runtask    = () -> { try { server.run(); } catch (IOException ioe) { } };
            Socket clientSocket = new Socket("localhost", port);
            Thread runthread    = new Thread(runtask);

            runthread.start();
            // ------------------

            byte content[] = path.getBytes(), response[];
            int responseCsize;
            ByteBuffer buffer = ByteBuffer.allocate(BFTP.HEADER_LENGTH + content.length);
            DataInputStream input = new DataInputStream(clientSocket.getInputStream());
            StringBuilder testOutput = new StringBuilder();

            buffer.putInt(content.length);
            buffer.put(BFTP.GET);
            buffer.put(content);

            clientSocket.getOutputStream().write(buffer.array());
            clientSocket.getOutputStream().flush();

            // Blocks until we get a response
            responseCsize = input.readInt();
            response = new byte[responseCsize];

            testOutput.append("Received response:\n");
            testOutput.append("\tcsize:   " + responseCsize + "\n");
            testOutput.append("\topcode:  " + input.readByte() + "\n");

            for (int i = 0; i < responseCsize; i++) {
                response[i] = input.readByte();
            }

            testOutput.append("\tcontent: " + new String(response));

            System.out.println(testOutput);

            server.shutdown();
            clientSocket.close();
        }
        catch (BindException be) {
            System.err.println("ERROR: Could not create BFTPServer, port " + port
                    + " already bound");
        }
        catch (IOException ioe) {
            System.err.println("ERROR: Could not create BFTPServer");
        }
    }

    public static void testUnsupportedCommand() {
        int port = 0xFADE;

        try {
            BFTPServer server   = new BFTPServer(port, System.out);
            Runnable runtask    = () -> { try { server.run(); } catch (IOException ioe) { } };
            Socket clientSocket = new Socket("localhost", port);
            Thread runthread    = new Thread(runtask);

            runthread.start();
         // ------------------

            ByteBuffer buffer = ByteBuffer.allocate(BFTP.HEADER_LENGTH);

            buffer.putInt(0); // no content
            buffer.put((byte) 0);

            clientSocket.getOutputStream().write(buffer.array());
            clientSocket.getOutputStream().flush();

            // TODO: Listen to response

            // Wait a reasonable amount of time
            Thread.sleep(2000);
            server.shutdown();
            clientSocket.close();
        }
        catch (InterruptedException ie) {
            System.err.println("ERROR: Interrupted while sleeping, make sure port " + port
                    + "is closed");
        }
        catch (BindException be) {
            System.err.println("ERROR: Could not create BFTPServer, port " + port
                    + " already bound");
        }
        catch (IOException ioe) {
            System.err.println("ERROR: Could not create BFTPServer");
        }
    }

    public static void main(String args[]) {
        testGetNonexistent();
    }
}
