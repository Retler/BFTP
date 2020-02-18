import java.net.Inet4Address;
import java.net.InetAddress;

public class Client {
    private int transferId;
    private InetAddress ip;
    private int port;

    public Client(int transferId, InetAddress ip, int port){
        this.transferId = transferId;
        this.ip = ip;
        this.port = port;
    }

    public int getTransferId() {
        return transferId;
    }

    public InetAddress getIp() {
        return ip;
    }

    public int getPort() {
        return port;
    }

    public boolean equals(Object other){
        if(other == this){
            return true;
        }
        if(!(other instanceof  Client)){
            return false;
        }
        Client otherClient = (Client) other;

        return otherClient.getIp() == ip && otherClient.getTransferId() == transferId && otherClient.getPort() == port;
    }

    public int hashCode(){
        return  11 * 31 * transferId * port * ip.hashCode();
    }
}
