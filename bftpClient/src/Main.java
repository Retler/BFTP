import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

public class Main {
    private static String fileName;
    private static long fileSize;
    private static File file;
    private static int numberOfPackets;
    private static byte[] packet;
    private static byte[] buffer;
    private static int transferId;
    private static byte[] ack;
    private static boolean[] receivedAcks;
    private static int windowStart;
    private static int windowsEnd;
    private static InetAddress ip;
    private static ByteBuffer bb;
    private static InputStream inputStream;
    private static DatagramSocket clientSocket;
    private static Map<Integer, Packet> pendingAcks;
    private static int bytesTransferedFromCurrentBuffer;
    private static int remainingSizeInBuffer;
    private static long timeOfUploadStart;
    private static int assignedPort;
    private static int windowSize;
    private static int blockSize;
    private static int packetSize;
    private static double plp;
    private static boolean shouldPacketDrop;
    private static int retransmissionTimeout;
    private static int retransmittedPackets;
    private static int packetsDroppedByPLP;

    public static void main(String[] args) throws IOException {
        if(args.length != 3 && args.length != 4 && args.length != 5){
            System.out.println("The input must be of form: file windowsize packet-payloadsize");
            System.out.println("PLP (packet loss probability) is an optional 4th parameter");
            System.out.println("Packet retransmission timeout in ms is the 5th optional parameter");
            System.exit(0);
        }

        if(args.length >= 4){
            plp = Double.parseDouble(args[3]);
        }else{
            plp = 0;
        }

        if(args.length == 5){
            retransmissionTimeout = Integer.parseInt(args[4]);
        }else{
            retransmissionTimeout = 50;
        }

        fileName = args[0];
        file = new File(fileName);

        if(file == null || !file.exists()){
            System.out.println("The provided file or path doesn't exist. Remember to include file extension.");
            System.exit(0);
        }

        packetsDroppedByPLP = 0;
        windowSize = Integer.parseInt(args[1]);
        blockSize = Integer.parseInt(args[2]);
        packetSize = 16+blockSize;

        pendingAcks = Collections.synchronizedMap(new HashMap<Integer, Packet>(windowSize*2)); // We prepare to fill up two windowful of packets
        windowStart = 0;
        windowsEnd = windowSize-1;
        packet = new byte[packetSize]; // packet holder, we will use the same byte array
        buffer = new byte[Settings.fileBufferSize]; // byte holder for file bites - we will read a buffer-full of bytes at a time
        transferId = new Random().nextInt();
        ack = new byte[8]; // for receiving acks
        bytesTransferedFromCurrentBuffer = 0;
        remainingSizeInBuffer = 0; // Buffer is not filled yet
        fileSize = file.length();
        numberOfPackets = (int)Math.ceil(((double)fileSize)/blockSize);
        receivedAcks = new boolean[numberOfPackets]; // We want one ack for each packet sent
        inputStream = new FileInputStream(file);

        // Building the first packet
        bb = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN);
        bb.putInt(transferId);
        bb.putLong(fileSize);
        ip = InetAddress.getByName(Settings.serverIp);
        clientSocket = new DatagramSocket();

        timeOfUploadStart =  System.nanoTime();

        // Establish connection by sending 1 packet and setting the port number for future packets
        establishConnection();

        // Sending initial packets in the window
        for(int i = 0; i < windowSize; i++){
            DatagramPacket packet = constructPacketFromBuffer(i);
            shouldPacketDrop = plp == 0 ? false : (new Random().nextInt((int)(100000/((plp*10)))) < 100);

            if(shouldPacketDrop){
                // Dropping the packet
                System.out.println("Packet " + i + " has been dropped by PLP");
                packetsDroppedByPLP++;
            }else{
                clientSocket.send(packet);
                System.out.println("Packet number " + i + " sent");
            }

            pendingAcks.put(i, new Packet(System.nanoTime(), packet.getData().clone()));

            if(i == numberOfPackets-1){ // The last packet is sent
                break;
            }
        }

        // Schedule a timer to check for unreceived acks and resend packets
        Timer timer = new Timer();
        PendingAcksTimer retransmissionTimer = new PendingAcksTimer(pendingAcks,clientSocket, assignedPort, plp, retransmissionTimeout);
        timer.schedule(retransmissionTimer, retransmissionTimeout, retransmissionTimeout); // Time in milliseonds -> 1000ms = 1s

        // Listen for acks
        while (true){
            DatagramPacket receivePacket = new DatagramPacket(ack, ack.length);
            Boolean data = false;
            while (!data) {
                try {
                    clientSocket.receive(receivePacket);
                    data = true;
                } catch (SocketTimeoutException e) {
                    continue;
                }
            }



            ByteBuffer ackBuffer = ByteBuffer.wrap(ack).order(ByteOrder.BIG_ENDIAN);
            //int transferId = ackBuffer.getInt(0);
            int ackedPacketNumber = ackBuffer.getInt(4);

            receivedAcks[ackedPacketNumber] = true;
            pendingAcks.remove(ackedPacketNumber); // This removal caused race condition in resendPacket-method

            System.out.println("Packet number " + ackedPacketNumber + " acknowledge received");

            if(receivedAcks[numberOfPackets-1]){ // Last packed received and no packets pending
                if(pendingAcks.isEmpty()) { // This check is nested so we limit access to a shared resource
                    retransmittedPackets = retransmissionTimer.getResentPackets();
                    packetsDroppedByPLP = packetsDroppedByPLP + retransmissionTimer.getPacketsDroppedByPLP();
                    timer.cancel();
                    timer.purge(); // Kill the running PendingAcksTimer thread
                    break;
                }
            }
            notifyPacketReceived();
        }

        int timeOfUploadInMilliseconds = (int)((System.nanoTime() - timeOfUploadStart)/1000000);
        System.out.println("File size: " + round(((double)fileSize)/1024,3) + " kbytes");
        System.out.println("Block size: " + blockSize + " bytes");
        System.out.println("Window size: " + windowSize + " packets");
        System.out.println("Time of upload in ms: " + timeOfUploadInMilliseconds + " ms");
        System.out.println("Transfer rate: " + (int)((((double)fileSize)/1024)/(((double)timeOfUploadInMilliseconds)/1000)) + " kb/s");
        System.out.println("Number of retransmitted packets: " + retransmittedPackets);
        System.out.println("Packet retransmission rate: " + retransmissionTimeout + "ms");
        System.out.println("Number of packets dropped by PLP: " + packetsDroppedByPLP);
        System.out.println("PLP: " + plp);

        clientSocket.close();
    }

    private static void establishConnection() throws IOException {
        DatagramPacket packet = generateInitialPacket();
        clientSocket.setSoTimeout(5000);

        clientSocket.send(packet);
        System.out.println("Initial packet sent. Waiting for assigned port.");
        DatagramPacket receivePacket = new DatagramPacket(ack, ack.length);

        try{
            clientSocket.receive(receivePacket);
        }catch (SocketTimeoutException e){
            System.out.println("Nothing heard from server for 5 sec. Connection could not be established.");
            System.exit(0);
        }

        assignedPort = ByteBuffer.wrap(receivePacket.getData()).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    public static double round(double value, int places) {
        if (places < 0) throw new IllegalArgumentException();

        BigDecimal bd = new BigDecimal(value);
        bd = bd.setScale(places, RoundingMode.HALF_UP);
        return bd.doubleValue();
    }

    private static DatagramPacket generateInitialPacket() {
        return new DatagramPacket(ByteBuffer.wrap(new byte[12]).order(ByteOrder.BIG_ENDIAN).putInt(transferId).putLong(fileSize).array(), 12, ip, Settings.serverPort);
    }

    private static DatagramPacket constructPacketFromBuffer(int packetNumber) throws IOException {
        int remainingSizeInPacket = blockSize;
        bb.position(12);
        bb.putInt(packetNumber);

        if(remainingSizeInBuffer == 0){
            inputStream.read(buffer);
            remainingSizeInBuffer = buffer.length;
            bytesTransferedFromCurrentBuffer = 0;
        }

        while(true){
            if(remainingSizeInBuffer >= remainingSizeInPacket){ // remaining buffer is bigger than blocksize
                bb.put(Arrays.copyOfRange(buffer,bytesTransferedFromCurrentBuffer,bytesTransferedFromCurrentBuffer+remainingSizeInPacket));
                remainingSizeInBuffer -= remainingSizeInPacket;
                bytesTransferedFromCurrentBuffer += remainingSizeInPacket;
                remainingSizeInPacket = blockSize; // reset remaining size in packet since current packet is filled and being sent

                break;
            }
            else
            { // remaining buffer is smaller than remaining packet size
                bb.put(Arrays.copyOfRange(buffer,bytesTransferedFromCurrentBuffer,buffer.length)); // At this point, the whole buffer is read
                inputStream.read(buffer); // Buffer is now empty, load more bytes from file
                remainingSizeInPacket -= remainingSizeInBuffer;
                bytesTransferedFromCurrentBuffer = 0;
                remainingSizeInBuffer = buffer.length;
            }
        }
        return new DatagramPacket(bb.array(), bb.array().length, ip, assignedPort);
    }

    private static void notifyPacketReceived() throws IOException {
        if(receivedAcks[windowStart]){
            sendPacketsInNewWindow();
        }
    }

    private static void sendPacketsInNewWindow() throws IOException {
        while(receivedAcks[windowStart] && windowsEnd <= numberOfPackets-2){ // While consecutive packets in window are received, increase windowsize and send new packets

            if(windowsEnd == numberOfPackets-1){
                System.nanoTime();
            }

            windowStart++;
            windowsEnd++;

            DatagramPacket sendPacket = constructPacketFromBuffer(windowsEnd);
            shouldPacketDrop = plp == 0 ? false : (new Random().nextInt((int)(100000/((plp*10)))) < 100);

            if(shouldPacketDrop){
                // Dropping the packet
                System.out.println("Packet number " + windowsEnd + " has been dropped by PLP");
                packetsDroppedByPLP++;
            }else{
                clientSocket.send(sendPacket);
                System.out.println("Packet number " + windowsEnd + " sent");
            }

            pendingAcks.put(windowsEnd, new Packet(System.nanoTime(), bb.array().clone()));


        }
    }
}


