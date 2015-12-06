package net.nolasaint.bftp.test;

import net.nolasaint.bftp.BFTP;
import net.nolasaint.bftp.impl.BFTPServer;

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
        testUnsupportedCommand();
    }
}
