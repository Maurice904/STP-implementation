import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;


public class Receiver {
    private int recvPort;
    private int senderPort;
    private InetAddress address;
    private volatile boolean isRunning;
    private DatagramSocket socket;
    private String fileName;
    private int maxWin;
    private HashMap<Integer, byte[]> buffer = new HashMap<>();
    private int expectedSeqNo;
    private volatile boolean appendTo = false;
    private int originalDataRcv = 0;
    private int orignalSegmentsRcv = 0;
    private int dupDataRcv = 0;
    private int dupAckSnd = 0;
    private PrintWriter logWriter;
    private long startingTime;
    private HashSet<Integer> seenRcv = new HashSet<>();
    private HashSet<Integer> seenSnd = new HashSet<>();
    private int curNo;

    public Receiver(int recvPort, int senderPort, String fileName, int maxWin) {
        this.recvPort = recvPort;
        this.senderPort = senderPort;
        this.fileName = fileName;
        this.maxWin = maxWin;
        this.isRunning = true;
    }

    public void start() throws IOException {
        socket = new DatagramSocket(recvPort);
        address = InetAddress.getByName("localhost");

        File logFile = new File("receiver_log.txt");
        logWriter = new PrintWriter(new FileOutputStream(logFile, false));

        receiveAndRespond();
        writeStats();
        logWriter.close();
    }

    private void receiveAndRespond() throws IOException {
        byte[] incomingData = new byte[1010];
        DatagramPacket packet = new DatagramPacket(incomingData, incomingData.length);

        while (isRunning) {
            try {
                socket.receive(packet);
                ByteBuffer wrapped = ByteBuffer.wrap(packet.getData(), 0, packet.getLength());
                short type = wrapped.getShort();
                int seqNo = wrapped.getShort() & 0xFFFF;
                byte[] data = new byte[packet.getLength() - 4];
                wrapped.get(data);

                if (type == 2) {
                    startingTime = System.currentTimeMillis();
                    logEvent("rcv", System.currentTimeMillis() - startingTime, "SYN", seqNo,0);
                    sendAck((seqNo + 1) % 65536);
                    expectedSeqNo = (seqNo + 1) % 65536;

                }
                else if (type == 0) {
                    logEvent("rcv", System.currentTimeMillis() - startingTime, "DATA", seqNo, data.length);
                    if (!seenRcv.contains(seqNo)) {
                        seenRcv.add(seqNo);
                        originalDataRcv += data.length;
                        orignalSegmentsRcv ++;
                    } else {
                        dupDataRcv ++;
                    }
                    if (seqNo == expectedSeqNo) {
                        writeDataToFile(data);
                        appendTo = true;
                        expectedSeqNo = (expectedSeqNo + data.length) % 65536;
                        checkBufferAndWrite();

                    } else {
                        buffer.put(seqNo, data);
                    }
                    sendAck(expectedSeqNo);
                }
                else if (type == 3) {
                    logEvent("rcv", System.currentTimeMillis() - startingTime, "FIN", seqNo, 0);
                    sendAck(seqNo + 1);
                    terminateConnection();
                }
            } catch (IOException e) {
                e.printStackTrace();
                break;
            }
        }
        socket.close();
    }

    private void terminateConnection() throws IOException {
        long curTime = System.currentTimeMillis();
        byte[] incomingData = new byte[4];
        DatagramPacket packet = new DatagramPacket(incomingData, incomingData.length);

        while (System.currentTimeMillis() - curTime < 2000) {
            try {
                socket.receive(packet);
                ByteBuffer wrapped = ByteBuffer.wrap(packet.getData(), 0, packet.getLength());
                short type = wrapped.getShort();
                int seqNo = wrapped.getShort() & 0xFFFF;

                if (type == 3) {
                    sendAck(seqNo + 1);
                    dupAckSnd++;
                }
            } catch (IOException e) {
                e.printStackTrace();
                break;
            }
        }
        isRunning = false;

    }

    private void writeDataToFile(byte[] data) {

        try (FileOutputStream fos = new FileOutputStream(fileName, appendTo)) {

            fos.write(data);
        } catch (IOException e) {
            System.err.println("Error writing to file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void checkBufferAndWrite() throws IOException {
        while (buffer.containsKey(expectedSeqNo)) {
            byte[] data = buffer.get(expectedSeqNo);
            writeDataToFile(data);
            buffer.remove(expectedSeqNo);
            expectedSeqNo = (expectedSeqNo + data.length) % 65536;

        }
    }

    private void sendAck(int ackSeqNo) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.putShort((short) 1); // ACK packet type
        buffer.putShort((short) ackSeqNo);

        DatagramPacket ackPacket = new DatagramPacket(buffer.array(), buffer.capacity(), address, senderPort);
        socket.send(ackPacket);
        if (!seenSnd.contains(ackSeqNo)) {
            seenSnd.add(ackSeqNo);
        } else {
            dupAckSnd ++;
        }
        logEvent("snd", System.currentTimeMillis() - startingTime, "ACK", ackSeqNo, 0);
        System.out.println("sent ack with seq no " + ackSeqNo);
    }
    private void logEvent(String action, long time, String type, int seqNo, int bytes) {
        logWriter.printf("%s %s.%s %s %d %d%n", action, time / 1000, String.format("%03d", time % 1000), type, seqNo, bytes);
    }

    private void writeStats() {
        logWriter.println("Original data received: " + originalDataRcv);
        logWriter.println("Original segments received: " + orignalSegmentsRcv);
        logWriter.println("Dup data segments received: " + dupDataRcv);
        logWriter.println("Dup ack segments sent: " + dupAckSnd);

    }
    public static void main(String[] args) {
        if (args.length != 4) {
            System.err.println("Usage: java Receiver <recv port> <sender port> <file name> <max window>");
            System.exit(1);
        }

        int recvPort = Integer.parseInt(args[0]);
        int senderPort = Integer.parseInt(args[1]);
        String fileName = args[2];
        int maxWin = Integer.parseInt(args[3]);

        Receiver receiver = new Receiver(recvPort, senderPort, fileName, maxWin);
        try {
            receiver.start();
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}