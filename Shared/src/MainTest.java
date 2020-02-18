import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

public class MainTest {
    private static HashMap<Integer, String> mymap;

    public static void main(String[] args){
        ByteBuffer fileByteBuffer = ByteBuffer.wrap(new byte[100]).order(ByteOrder.BIG_ENDIAN);
        byte[] data = new byte[123];
        data[0] = 123;
        fileByteBuffer.put(Arrays.copyOfRange(data,0, 1));
        fileByteBuffer.array();
    }
}
