/**
 * Created by Kultur-Kanalen on 14-05-2018.
 */
public class Packet {
    private long packetSentAt;
    private byte[] packetData;

    public Packet(long packetSentAt, byte[] packetData){
        this.packetSentAt = packetSentAt;
        this.packetData = packetData;
    }

    public long getPacketTimestamp() {
        return packetSentAt;
    }

    public byte[] getPacketData() {
        return packetData;
    }

    public void setPacketTimestamp(long newTimeInNanoSeconds){
        packetSentAt = newTimeInNanoSeconds;
    }


}
