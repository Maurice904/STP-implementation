import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;

public class Sender {
    // Server details and operation duration
    private String host = "localhost";
    private int recvPort;
    private int senderPort;
    private int timer;
    private volatile boolean isRunning;
    private DatagramSocket socket;
    private String fileName;
    private int maxWin;
    private double flp;
    private double rlp;
    private InetAddress address;
    private int seqNo; //the expected sequence Number to sent next
    private int expectedSeqNo = -1; // the expected ack seq number from receiver
    private byte[] fileData;
    private int recvAck = -1; //keep track of the lastest ack recived
    private int dupAckCount = 0;
    private int originalDataSent = 0;
    private int originalDataAcked = 0;
    private int originalSegmentsSent = 0;
    private int retransmittedSegments = 0;
    private int duplicateAcksReceived = 0;
    private int dataSegmentsDropped = 0;
    private int ackSegmentsDropped = 0;
    private PrintWriter logWriter;
    private long startingTime;
    private HashSet<Integer> seenSnd = new HashSet<>();
    private HashSet<Integer> seenRcv = new HashSet<>();
    private Map<Integer, Integer> sentSegments = new HashMap<>();
    private int synAck = -1;
    private int finAck = -1;
    private HashMap<Integer, Integer> ackDataSize = new HashMap<>();
    private int[] maxSeq = new int[] {0, 0};


    public Sender(int senderPort, int recvPort, String fileName, int maxWin, int timer, double flp, double rlp) {
        this.senderPort = senderPort;
        this.recvPort = recvPort;
        this.fileName = fileName;
        this.maxWin = maxWin;
        this.timer = timer;
        this.flp = flp;
        this.rlp = rlp;
        this.isRunning = true;


    }

    public void start() throws IOException {
        socket = new DatagramSocket(senderPort);
        address = InetAddress.getByName(host);
        socket.connect(new InetSocketAddress(host, recvPort));

        File logFile = new File("sender_log.txt");
        logWriter = new PrintWriter(new FileOutputStream(logFile, false));

        new ReceiveThread().start();

        Random random = new Random();
        seqNo = random.nextInt(65536);

        loadFile();
        initiateConnection();
        sendFile();
        terminateConnection();
        writeStats();
        logWriter.close();
        int data = 0;
        for (Map.Entry <Integer, Integer> pair: ackDataSize.entrySet()) {
            data += pair.getValue();
        }
        System.out.println(data);
    }

    private void initiateConnection() throws IOException {
        startingTime = System.currentTimeMillis();
        while (seqNo + 1 != expectedSeqNo) {
            long startTime = System.currentTimeMillis();


            if (new Random().nextDouble() > flp) {
                synOrFin(2);

            } else {
                logEvent("drp", System.currentTimeMillis() - startingTime, "SYN", seqNo, 0);
            }
            expectedSeqNo = waitForACK(startTime);

        }
        seqNo = (seqNo + 1) % 65536;;

    }

    private void terminateConnection() throws IOException {
        int attempt = 10;

        while (attempt > 0 && seqNo + 1 != expectedSeqNo) {
            long startTime = System.currentTimeMillis();

            if (new Random().nextDouble() > flp) {
                synOrFin(3);
            } else {
                logEvent("drp", System.currentTimeMillis() - startingTime, "FIN", seqNo, 0);
            }

            expectedSeqNo = waitForACK(startTime);
            System.out.println("expectedSeqNo is " + expectedSeqNo);
            attempt --;
        }
        System.out.println("terminated");
        isRunning = false;
        socket.close();
    }

    private void synOrFin(int type) throws IOException {
        if (type == 2) {
            logEvent("snd", System.currentTimeMillis() - startingTime, "SYN", seqNo, 0);
            synAck = seqNo + 1;

        } else {
            finAck = seqNo + 1;

            logEvent("snd", System.currentTimeMillis() - startingTime, "FIN", seqNo, 0);
        }

        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.putShort((short) type);
        buffer.putShort((short) seqNo);

        DatagramPacket packet = new DatagramPacket(buffer.array(), buffer.capacity(), address, recvPort);
        socket.send(packet);
        System.out.println("Sent: " + (type == 2 ? "SYN" : "FIN") + ", seqNo=" + seqNo + "expected " + (seqNo + 1));
    }
    private void loadFile() throws IOException {
        File file = new File(fileName);
        FileInputStream fis = new FileInputStream(file);
        fileData = new byte[(int) file.length()];
        fis.read(fileData);
        fis.close();
    }


    private void sendFile() throws IOException {
        int offset = 0;

        while (offset < fileData.length && isRunning) {
            int windowSize = 0;
            boolean mark = false;
            long startTime = 0;
            while (windowSize < maxWin && offset < fileData.length) {
                if (!mark) {
                    startTime = System.currentTimeMillis();
                    mark = true;
                }
                int segmentSize = Math.min(1000, fileData.length - offset);
                windowSize += segmentSize;
                if (new Random().nextDouble() > flp) {
                    sendSegment(fileData, offset, segmentSize);

                } else {
                    dataSegmentsDropped ++;
                    logEvent("drp", System.currentTimeMillis() - startingTime, "DATA", seqNo, segmentSize);
                }
                sentSegments.put(seqNo, offset);
                seqNo = (seqNo + segmentSize) % 65536;
                offset += segmentSize;
                if (offset > maxSeq[1]) {
                    maxSeq[0] = seqNo;
                    maxSeq[1] = offset;
                }


            }
            expectedSeqNo = waitForACK(startTime);
            // implement Go-Back-N
            if (modCompare(expectedSeqNo, seqNo)) {
                seqNo = expectedSeqNo;
                offset = sentSegments.get(seqNo);
            } else if (modCompare(seqNo, expectedSeqNo)) {
                seqNo = expectedSeqNo;
                offset = sentSegments.getOrDefault(seqNo,maxSeq[1]);
            }

        }
    }

    //if successfully passing loss check, send data
    private void sendSegment(byte[] data, int offset, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4 + length);
        buffer.putShort((short) 0);
        buffer.putShort((short) seqNo);
        buffer.put(data, offset, length);

        DatagramPacket packet = new DatagramPacket(buffer.array(), buffer.capacity(), address, recvPort);
        socket.send(packet);
        ackDataSize.put((seqNo + length) % 65536, length);
        if (!seenSnd.contains(seqNo)) {
            seenSnd.add(seqNo);
            originalDataSent += length;
            originalSegmentsSent ++;
        } else {
            retransmittedSegments ++;
        }


        logEvent("snd", System.currentTimeMillis() - startingTime, "DATA", seqNo, length);
        System.out.println("Sent: seqNo= " + seqNo + ", size= " + length + " expected " + (seqNo + length));
    }

    // Thread for handling received responses from the receiver
    class ReceiveThread extends Thread {
        public void run() {
            try {
                while (isRunning) {
                    byte[] buffer = new byte[4];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    ByteBuffer wrapped = ByteBuffer.wrap(packet.getData());
                    short type = wrapped.getShort();
                    int ackSeqNo = wrapped.getShort() & 0xFFFF;
                    System.out.println("rcv ackSeq " + ackSeqNo);

                    if (new Random().nextDouble() > rlp) {
                        logEvent("rcv", System.currentTimeMillis() - startingTime, "ACK", ackSeqNo, 0);

                        if (!seenRcv.contains(ackSeqNo) ) {
                            seenRcv.add(ackSeqNo);
                            originalDataAcked += ackDataSize.getOrDefault(ackSeqNo, 0);
                        } else  {
                            duplicateAcksReceived ++;
                        }
                        if (recvAck == -1) {
                            recvAck = ackSeqNo;
                            continue;
                        }
                        if (recvAck == ackSeqNo) {
                            dupAckCount += 1;
                        } else if (modCompare(recvAck, ackSeqNo)){
                            dupAckCount = 0;
                            recvAck = ackSeqNo;
                        }
                    } else {
                        logEvent("drp", System.currentTimeMillis() - startingTime, "ACK", ackSeqNo, 0);
                        ackSegmentsDropped ++;
                    }

                }
            } catch (IOException e) {
                if (!isRunning) return; // Exits if the sender is no longer running
            }
        }
    }

    //comparison with mod involov
    private boolean modCompare(int currentAck, int newAck) {

        int diff = (newAck - currentAck + 65536) % 65536;
        return diff > 0 && diff < 32768; // return true if newAck > curAck consider the case (65000 + 1000) % 65536 > 64000
    }

    //implement timeout
    private int waitForACK(long startTime) {

        while (System.currentTimeMillis() - startTime < timer) {
            if (dupAckCount >= 3) {
                return recvAck;
            }
            if (recvAck == seqNo ||modCompare(seqNo,recvAck)) {
                return recvAck;
            }
        }


        return recvAck;

    }
    private void logEvent(String action, long time, String type, int seqNo, int bytes) {
        logWriter.printf("%s %s.%s %s %d %d%n", action, time / 1000, String.format("%03d", time % 1000), type, seqNo, bytes);
    }

    private void writeStats() {
        logWriter.println("Original data sent: " + originalDataSent);
        logWriter.println("Original data acked: " + originalDataAcked);
        logWriter.println("Original segments sent: " + originalSegmentsSent);
        logWriter.println("Retransmitted segments: " + retransmittedSegments);
        logWriter.println("Dup acks received: " + duplicateAcksReceived);
        logWriter.println("Data segments dropped: " + dataSegmentsDropped);
        logWriter.println("Ack segments dropped: " + ackSegmentsDropped);
    }

    public static void main(String[] args) {
        // Ensures the correct number of arguments are provided
        if (args.length != 7) {
            System.err.println("Usage: java Sender <sender port> <recv port> <file name> <max window> <timer> <flp> <rlp>");
            System.exit(1);
        }

        // Initializes and starts the sender with the provided arguments
        int senderPort = Integer.parseInt(args[0]);
        int recvPort = Integer.parseInt(args[1]);
        String fileName = args[2];
        int maxWin = Integer.parseInt(args[3]);
        if (maxWin % 1000 != 0) {
            System.err.println("max window size should be multiple of 1000");
            System.exit(1);
        }
        int timer = Integer.parseInt(args[4]);
        double flp = Double.parseDouble(args[5]);
        double rlp = Double.parseDouble(args[6]);
        Sender sender = new Sender(senderPort, recvPort, fileName, maxWin, timer, flp, rlp);
        try {
            sender.start();
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
