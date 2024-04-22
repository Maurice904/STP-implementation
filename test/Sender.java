package test;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;

public class Sender {
    private String host;
    private int port;
    private int timer;
    private volatile boolean isRunning;
    private DatagramSocket socket;
    private InetAddress address;

    private String fileName;
    private int maxWin;
    private int seqNo = 0;
    private byte[] fileData;

    public Sender(String host, int port, String fileName, int maxWin, int timer) {
        this.host = host;
        this.port = port;
        this.fileName = fileName;
        this.maxWin = maxWin;
        this.timer = timer;
        this.isRunning = true;
    }

    public void start() throws IOException {
        socket = new DatagramSocket();
//        address = InetAddress.getByName(host);
        socket.connect(new InetSocketAddress(host, port));
        loadFile();
        initiateConnection();
        sendFile();
        terminateConnection();
    }

    private void initiateConnection() throws IOException {
        sendControlPacket(2); // SYN = 2
        waitForACK();
    }

    private void terminateConnection() throws IOException {
        sendControlPacket(3); // FIN = 3
        waitForACK();
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
            int segmentSize = Math.min(maxWin, fileData.length - offset);
            sendSegment(fileData, offset, segmentSize);
            waitForACK();
            offset += segmentSize;
            seqNo = (seqNo + segmentSize) % 65536;
        }
    }

    private void sendSegment(byte[] data, int offset, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4 + length);
        buffer.putShort((short) 0); // DATA type = 0
        buffer.putShort((short) seqNo);
        buffer.put(data, offset, length);

        DatagramPacket packet = new DatagramPacket(buffer.array(), buffer.capacity(), address, port);
        socket.send(packet);
        System.out.println("Sent: seqNo=" + seqNo + ", size=" + length);
    }

    private void sendControlPacket(int type) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.putShort((short) type); // Control type (SYN=2 or FIN=3)
        buffer.putShort((short) seqNo);

        DatagramPacket packet = new DatagramPacket(buffer.array(), buffer.capacity(), address, port);
        socket.send(packet);
        System.out.println("Sent: " + (type == 2 ? "SYN" : "FIN") + ", seqNo=" + seqNo);
    }

    private void waitForACK() {
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < timer * 1000 && !isRunning) {
            try {
                Thread.sleep(10); // Check for ACK every 10ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (!isRunning) {
            System.out.println("Timeout reached, resending...");
            seqNo--; // Decrement for SYN/FIN retransmit
        }
    }

    class ReceiveThread extends Thread {
        public void run() {
            try {
                while (isRunning) {
                    byte[] buffer = new byte[4];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    ByteBuffer wrapped = ByteBuffer.wrap(packet.getData());
                    short type = wrapped.getShort();
                    short ackSeqNo = wrapped.getShort();

                    if (type == 1 && ackSeqNo == seqNo + 1) { // ACK type = 1
                        isRunning = false; // Stop waiting for ACK
                        System.out.println("Received ACK for seqNo=" + ackSeqNo);
                    }
                }
            } catch (IOException e) {
                if (!isRunning) return; // Exits if the sender is no longer running
            }
        }
    }

    public static void main(String[] args) {
        if (args.length != 5) {
            System.err.println("Usage: java Sender <host> <port> <file name> <max window> <timer>");
            System.exit(1);
        }

        try {
            String host = args[0];
            int port = Integer.parseInt(args[1]);
            String fileName = args[2];
            int maxWin = Integer.parseInt(args[3]);
            int timer = Integer.parseInt(args[4]);
            Sender sender = new Sender(host, port, fileName, maxWin, timer);
            sender.start();
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}

