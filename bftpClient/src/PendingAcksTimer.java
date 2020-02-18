import java.io.IOException;
import java.net.*;
import java.util.Map;
import java.util.Random;
import java.util.TimerTask;

// TODO: Close the thread when transfer is done
class PendingAcksTimer extends TimerTask {
    private static Map<Integer, Packet> pendingAcks;
    private DatagramSocket socket;
    private int assignedPort;
    private boolean shouldPacketDrop;
    private double plp;
    private int retransmissionTimeout;
    private int resentPackets;
    private int packetsDroppedByPLP;

    public PendingAcksTimer(Map<Integer, Packet> pendingAcks, DatagramSocket socket, int assignedPort, double plp, int retransmissionTimeout){
        this.pendingAcks = pendingAcks;
        this.socket = socket;
        this.assignedPort = assignedPort;
        this.plp = plp;
        this.retransmissionTimeout = retransmissionTimeout;
        resentPackets = 0;
        packetsDroppedByPLP = 0;
    }

    public void run() {
        try {
            sendLostPackets();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public int getResentPackets(){
        return resentPackets;
    }

    private void sendLostPackets() throws IOException {
        synchronized (pendingAcks) {
            for(int i : pendingAcks.keySet()){
                if(System.nanoTime() - pendingAcks.get(i).getPacketTimestamp() > 1000000*retransmissionTimeout){ // If elapsed time is greater than retransmission timeout
                    resendPacket(pendingAcks.get(i).getPacketData(), i); // A RACE CONDITION CAN OCCUR BETWEEN HERE AND resendPacket method
                    resentPackets++;
                }
            }
        }
    }

    public int getPacketsDroppedByPLP(){
        return packetsDroppedByPLP;
    }

    private void resendPacket(byte[] packetData, int packetNumber) throws IOException {
        DatagramPacket packet = new DatagramPacket(packetData, packetData.length, InetAddress.getByName(Settings.serverIp), assignedPort);
        shouldPacketDrop = plp == 0 ? false : (new Random().nextInt((int)(100000/((plp*10)))) < 100);

        if(shouldPacketDrop){
            // Dropping the packet
            System.out.println("Resent packet with number " + packetNumber + " has been dropped by PLP");
            packetsDroppedByPLP++;
        }else{
            socket.send(packet);
            System.out.println("Packet number " + packetNumber + " resent");
        }

        if(pendingAcks.get(packetNumber) != null){ // If ack packet hasn't arrived meanwhile
            pendingAcks.get(packetNumber).setPacketTimestamp(System.nanoTime());
        }
    }
}
