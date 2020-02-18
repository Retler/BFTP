import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.*;

public class TransferThread implements Runnable {
    private DatagramSocket transferSocket;
    byte[] receiveData;
    private ByteBuffer ackBuffer = ByteBuffer.wrap(new byte[8]).order(ByteOrder.BIG_ENDIAN);
    private long fileSize;
    private int numberOfPackets;
    private int sizeOfLastPayload;
    private long dataCounter = 0;
    private boolean[] receivedPackets;
    private int windowStart;
    private int windowEnd;
    private boolean packetFallsWithinWindow;
    private InetAddress clientIp;
    private int clientPort;
    private int writtenPacketCount;
    private boolean shouldWindowSlide;
    private byte[] fileBuffer;
    private int remainingSizeInBuffer;
    private ByteBuffer fileByteBuffer;
    private int sizeOfLastBuffer;
    private FileOutputStream os;
    private int waitingForPacketNumber;
    private Map<Integer, byte[]> packetQueue;
    private int windowSize;
    private int blockSize;
    private int packetSize;

    public TransferThread(DatagramSocket transferSocket, long fileSize, Client client, int windowSize, int blockSize){
        this.windowSize = windowSize;
        this.blockSize = blockSize;
        this.transferSocket = transferSocket;
        this.fileSize = fileSize;
        packetSize = 16 + blockSize;
        receiveData = new byte[packetSize];
        numberOfPackets = (int)Math.ceil(((double)fileSize)/blockSize);
        sizeOfLastPayload = (int)(fileSize % blockSize);
        System.out.println("Size of last payload calculated to " + sizeOfLastPayload);
        System.out.println("Number of packets calculated to " + numberOfPackets);
        receivedPackets = new boolean[numberOfPackets];
        windowStart = 0;
        windowEnd = windowSize-1;
        clientIp = client.getIp();
        clientPort = client.getPort();
        writtenPacketCount = 0;
        fileBuffer = new byte[Settings.fileBufferSize];
        remainingSizeInBuffer = fileBuffer.length;
        fileByteBuffer = ByteBuffer.wrap(fileBuffer).order(ByteOrder.BIG_ENDIAN);
        sizeOfLastBuffer = (int)(fileSize % Settings.fileBufferSize);
        os = null;
        waitingForPacketNumber = 0;
        packetQueue = new HashMap<>();
    }

    @Override
    public void run() {
        String fileName = generateFileName();
        try {
            File outputFile = new File(fileName);
            os = new FileOutputStream(outputFile, true);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        while(true){
            DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
            try {
                transferSocket.receive(receivePacket);
            } catch (IOException e) {
                e.printStackTrace();
            }

            ByteBuffer bb = ByteBuffer.wrap(receiveData).order(ByteOrder.BIG_ENDIAN);

            int transferId = getTransferIdFromPacket(bb);
            int packetNumber = getPacketNumberFromPacket(bb);

            packetFallsWithinWindow = packetNumber >= windowStart && packetNumber <= windowEnd;

            try {
                if(packetFallsWithinWindow){
                    if(receivedPackets[packetNumber]){ // Packet has already been received, send an ack
                        System.out.println("Packet " + packetNumber + " received twice");
                        acknowledgePacket(transferId, packetNumber, receivePacket);
                    }
                    else
                    {
                        receivedPackets[packetNumber] = true;
                        shouldWindowSlide = receivedPackets[windowStart] && windowEnd < numberOfPackets;

                        acknowledgePacket(transferId, packetNumber, receivePacket);

                        if(shouldWindowSlide){
                            windowStart++;
                            windowEnd++;
                        }
                        byte[] data = getPayloadFromPacket(bb, packetNumber);

                        if(packetNumber == waitingForPacketNumber){
                            fillBufferWithPacketData(data);
                            fillBufferWithPacketsFromQueue();
                        }else{
                            System.out.println("Wrong sequence - enqueing packet");
                            enqueuePacket(packetNumber, data);
                        }



                        writtenPacketCount++;
                        dataCounter += data.length;
                    }
                }

            } catch (IOException e) {
                e.printStackTrace();
            }

            if(numberOfPackets-1 == packetNumber && allPacketsAreAcknowledged()){ // Continue listening for packets
                try {
                    emptyBuffer();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                break;
            }
        }

        System.out.println("Written packet count is " + writtenPacketCount);
        try {
            System.out.println("Closing filestream");
            os.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        System.out.println("Disconnecting socket " + transferSocket.getLocalPort());
        transferSocket.disconnect();
        transferSocket.close();
    }

    private String generateFileName() {
        Date date = new Date();
        SimpleDateFormat format = new SimpleDateFormat("dd-MM-yyyy HHmmss");
        return format.format(date);
    }

    private void fillBufferWithPacketsFromQueue() throws IOException {
        byte[] data = packetQueue.get(waitingForPacketNumber);
        if(data != null){
            packetQueue.remove(waitingForPacketNumber);
            fillBufferWithPacketData(data);
            fillBufferWithPacketsFromQueue();
        }
    }

    private void enqueuePacket(int packetNumber, byte[] data) {
        packetQueue.put(packetNumber, data);
    }

    private void emptyBuffer() throws IOException {
        os.write(Arrays.copyOfRange(fileByteBuffer.array(),0,sizeOfLastBuffer));
    }

    private void fillBufferWithPacketData(byte[] data) throws IOException {
        if(data.length <= remainingSizeInBuffer){
            fileByteBuffer.put(data);
            remainingSizeInBuffer -= blockSize;
            waitingForPacketNumber++;
        }else{
            fileByteBuffer.put(Arrays.copyOfRange(data,0, remainingSizeInBuffer));
            os.write(fileByteBuffer.array());
            fileByteBuffer.position(0);
            byte[] restOfPacketData = Arrays.copyOfRange(data,remainingSizeInBuffer, data.length);
            remainingSizeInBuffer = fileBuffer.length;
            fillBufferWithPacketData(restOfPacketData);
        }
    }

    private boolean allPacketsAreAcknowledged() {
        printReceivedPackets();
        int windowsCeiling = Math.min(windowStart+windowEnd, numberOfPackets-1);
        for(int i = windowStart; i < windowsCeiling; i++){
            if(!receivedPackets[i]){
                return false;
            }
        }
        return true;
    }

    private void printReceivedPackets() {
        for(int i = 0; i < receivedPackets.length; i++){
            System.out.print(receivedPackets[i] ? "1" : 0);
        }
        System.out.println();
    }

    private void acknowledgePacket(int transferId, int packetNumber, DatagramPacket receivePacket) throws IOException {
        ackBuffer.position(0);
        ackBuffer.putInt(transferId);
        ackBuffer.putInt(packetNumber);

        DatagramPacket sendPacket =
                new DatagramPacket(ackBuffer.array(), ackBuffer.array().length, clientIp, clientPort);
        transferSocket.send(sendPacket);
        System.out.println("Packet number " + packetNumber + " acknowledge sent");
    }

    private byte[] getPayloadFromPacket(ByteBuffer bb, int packetNumber){
        byte[] data;

        if(numberOfPackets-1 == packetNumber){ // We are at the last packet, extract only some of the packet
            data = Arrays.copyOfRange(bb.array(),16, sizeOfLastPayload+16);
        }else{
            data = Arrays.copyOfRange(bb.array(),16, packetSize);
        }

        return data;
    }

    private void fillBufferFromPacket(){

    }

    private static int getTransferIdFromPacket(ByteBuffer bb){
        return bb.getInt(0);
    }

    private static long getFileSizeFromPacket(ByteBuffer bb){
        return bb.getLong(4);
    }

    private static int getPacketNumberFromPacket(ByteBuffer bb){
        return bb.getInt(12);
    }
}
