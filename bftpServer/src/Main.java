import com.sun.xml.internal.ws.api.config.management.policy.ManagementAssertion;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;

public class Main {
    private static DatagramSocket serverSocket;
    private static Set<Client> clients;
    private static int windowSize;
    private static int blockSize;
    public static void main(String args[]) throws Exception
    {
        if(args.length != 2){
            System.out.println("Arguments must be of the form: windowsize packet-payloadsize");
            System.exit(0);
        }
        windowSize = Integer.parseInt(args[0]);
        blockSize = Integer.parseInt(args[1]);

        // The main listening socket, listening for initial packets
        serverSocket = new DatagramSocket(Settings.serverPort);
        clients = new HashSet<>();
        byte[] receiveData = new byte[12];

        //TODO: Implement packet sequencing (write data in the right order)(Sliding windows)
        while(true)
        {
            DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
            serverSocket.receive(receivePacket);
            InetAddress clientAdress = receivePacket.getAddress();
            int clientPort = receivePacket.getPort();
            ByteBuffer bb = ByteBuffer.wrap(receiveData).order(ByteOrder.BIG_ENDIAN);
            int transferId = bb.getInt();
            long fileSize = bb.getLong();
            Client client = new Client(transferId,clientAdress,clientPort);
            clients.add(client);

            System.out.println("Initial packet from " + clientAdress.getHostName() + ":"+clientPort + " received");

            DatagramSocket threadSocket = new DatagramSocket();
            byte[] threadPort = ByteBuffer.wrap(new byte[4]).order(ByteOrder.BIG_ENDIAN).putInt(threadSocket.getLocalPort()).array();
            DatagramPacket threadSocketPacket = new DatagramPacket(threadPort, threadPort.length, clientAdress, clientPort);
            serverSocket.send(threadSocketPacket); // TODO: This packet might get lost, implement countermeasures
            new Thread(new TransferThread(threadSocket, fileSize,client, windowSize,blockSize)).start();
        }
    }
}
