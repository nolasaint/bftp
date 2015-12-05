package net.nolasaint.bftp.test;

import net.nolasaint.bftp.impl.BFTPServer;

import java.io.IOException;
import java.net.Socket;

/**
 * Created by Evan on 12/2/2015.
 */
public class BFTPServerTests {

    public static void main(String args[]) {
        try {
            BFTPServer server = new BFTPServer(8008, System.out);
            Runnable task = () -> { try { server.run(); } catch (IOException ioex) { } };
            Thread runthread = new Thread(task);

            runthread.start();

            Socket clientSocket = new Socket("localhost", 8008);

            Thread.sleep(1000);

            server.shutdown();
        }
        catch (InterruptedException iex) {
        }
        catch (IOException ioex) {
        }
    }
}
