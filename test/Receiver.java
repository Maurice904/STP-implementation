import java.io.FileOutputStream;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.SortedMap;
import java.util.TreeMap;

public class Receiver {
    private int receiverPort;
    private int senderPort;
    private String fileName;
    private int maxWin;
    private volatile boolean isRunning;
    private DatagramSocket socket;
    private SortedMap<Integer, byte[]> buffer;

    public Receiver(int receiverPort, int senderPort, String fileName, int maxWin) {
        this.receiverPort = receiverPort;
        this.senderPort = senderPort;
        this.fileName = fileName;
        this.maxWin = maxWin;
        this.buffer = new TreeMap<>();
        this.isRunning = true;
    }

    public void start() throws IOException {
        socket = new DatagramSocket(receiverPort);
        receiveAndRespond();
    }

    private void receiveAndRespond() throws IOException {
        byte[] incomingData = new byte[maxWin + 10]; // Larger than max window size to accommodate headers
        DatagramPacket packet = new DatagramPacket(incomingData, incomingData.length);

        int expectedSeqNo = 0;

        while (isRunning) {
            try {
                socket.receive(packet);
                ByteBuffer wrapped = ByteBuffer.wrap(packet.getData());
                short type = wrapped.getShort();
                int seqNo = wrapped.getShort() & 0xFFFF;
                int dataSize = packet.getLength() - 4; // Subtract header size

                if (type == 0) { // Data packet
                    byte[] data = new byte[dataSize];
                    wrapped.get(data, 4, dataSize); // Extract the actual data ignoring the first 4 bytes (header)

                    if (seqNo == expectedSeqNo) {
                        processInOrderData(data, seqNo);
                        expectedSeqNo += dataSize;
                        checkBufferForContinuity();
                    } else {
                        buffer.put(seqNo, data); // Store out of order data in buffer
                    }

                    sendACK(seqNo + dataSize);
                } else if (type == 2 || type == 3) { // SYN or FIN
                    sendACK(seqNo + 1); // ACK for connection setup/teardown
                    if (type == 3) {
                        isRunning = false; // Stop the receiver if FIN packet received
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
                break;
            }
        }

        socket.close(); // Close the socket
        writeToFile(); // Write remaining data to file
    }

    private void sendACK(int ackSeqNo) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.putShort((short) 1); // ACK type
        buffer.putShort((short) ackSeqNo);

        DatagramPacket ackPacket = new DatagramPacket(buffer.array(), buffer.capacity(), InetAddress.getByName("localhost"), senderPort);
        socket.send(ackPacket);
    }

    private void processInOrderData(byte[] data, int seqNo) throws IOException {
        FileOutputStream fos = new FileOutputStream(fileName, true);
        fos.write(data);
        fos.close();
    }

    private void checkBufferForContinuity() throws IOException {
        while (buffer.containsKey(expectedSeqNo)) {
            byte[] data = buffer.remove(expectedSeqNo);
            processInOrderData(data, expectedSeqNo);
            expectedSeqNo += data.length;
        }
    }

    private void writeToFile() throws IOException {
        FileOutputStream fos = new FileOutputStream(fileName, true);
        buffer.values().forEach(data -> {
            try {
                fos.write(data);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        fos.close();
    }

    public static void main(String[] args) {
        if (args.length != 4) {
            System.err.println("Usage: java Receiver <receiver port> <sender port> <file name> <max window>");
            System.exit(1);
        }

        int receiverPort = Integer.parseInt(args[0]);
        int senderPort = Integer.parseInt(args[1]);
        String fileName = args[2];
        int maxWin = Integer.parseInt(args[3]);

        Receiver receiver = new Receiver(receiverPort, senderPort, fileName, maxWin);
        try {
            receiver.start();
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
