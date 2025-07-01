import javax.sound.sampled.*;
import java.net.*;
import java.util.Map;
import java.io.*;

/*
 * This class sends audio data data over a network connection. 
 * It implements the Runnable interface, allowing it to be executed as a separate thread.
 */
public class AudioSender implements Runnable {
    private String myIP;
    private boolean isPrivate;
    private int sequenceNumber = 0;
    private TargetDataLine line = null;
    private DatagramSocket socket = null;
    private Map <String, Integer> clients;
    private volatile boolean isRunning = true;
    private static final int PACKET_SIZE = 320;
    
    
    /*
     * initializes the object with the local IP address, 
     * a map of client IP addresses and ports, 
     * and a boolean indicating whether the call is private or a group call.
     */
    public AudioSender(String myIP, Map <String, Integer> clients, boolean isPrivate) {
        this.myIP = myIP;
        this .clients = clients;
        this.isPrivate = isPrivate;
    }

    /*
     * This method is called when the object is executed as a thread. 
     * It determines whether to make a private or group call based on the isPrivate flag and calls the corresponding method.
     */
    @Override
    public void run() {
        if (isPrivate) {
            privateCall();
        } else {
            groupCall();
        }   
    }

    /**
     * Handles the logic for sending audio data to a single receiver in a private call.
     * Initializes the audio line and DatagramSocket, reads audio data from the microphone,
     * and sends it as UDP packets to the specified receiver IP and port. 
     * Sequence numbers are added to each packet for proper ordering on the receiver's side.
     * If a network error occurs, it attempts to reconnect the socket.
     * Cleans up resources when finished.
     */

    private void privateCall() {
        AudioFormat format = getAudioFormat();
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        String receiverIP = clients.keySet().iterator().next();
        int port = clients.get(receiverIP);
        
        try {
            InetAddress receiverAddr = InetAddress.getByName(receiverIP);
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(format, PACKET_SIZE * 2); 
            line.start();

            socket = new DatagramSocket();
            
            byte[] buffer = new byte[PACKET_SIZE];
            ByteArrayOutputStream baos = new ByteArrayOutputStream(PACKET_SIZE + 4);
            DataOutputStream dos = new DataOutputStream(baos);
            
            while (isRunning) {
                try {
                    // Read audio data
                    int bytesRead = line.read(buffer, 0, buffer.length);

                    if (bytesRead <= 0) continue;

                    // Boas stores all data read in from the beginning so we reset it eveyrtime
                    baos.reset();
                    dos.writeInt(sequenceNumber++);
                    baos.write(buffer, 0, bytesRead);
                    byte[] packetData = baos.toByteArray();

                    DatagramPacket packet = new DatagramPacket(packetData, packetData.length, receiverAddr, port);
                    socket.send(packet);


                } catch (IOException e) {
                    System.err.println("Network error: " + e.getMessage());
                    reconnectSocket();
                }
            }
        } catch (Exception e) {
            System.err.println("AudioSender error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            cleanUp();
        }
    }
    
    /**
     * Attempts to reconnect the DatagramSocket if it is not already closed.
     * This is called when a network error occurs in the private call or group call.
     * This method is synchronized so that only one thread can reconnect the socket at a time.
     */
    private void reconnectSocket() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            socket = new DatagramSocket();
            socket.setSendBufferSize(4096);
        } catch (IOException e) {
            System.err.println("Socket reconnection failed: " + e.getMessage());
        }
    }

    /**
     * Handles the logic for sending audio data to multiple receivers in a group call.
     * Initializes the audio line and DatagramSocket, reads audio data from the microphone,
     * and sends it as UDP packets to all specified receiver IP addresses and ports.
     * Sequence numbers are added to each packet for proper ordering on the receiver's side.
     * If a network error occurs, it attempts to reconnect the socket and continue sending to other receivers.
     * Cleans up resources when finished.
     */
    private void groupCall() {
        AudioFormat format = getAudioFormat();
        String[] receivers = clients.keySet().toArray(new String[0]);
        InetAddress[] receiverAddrs = new InetAddress[receivers.length];
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        try {
            // Set up audio line
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(format, PACKET_SIZE * 2);
            line.start();
    
            // Create socket
            socket = new DatagramSocket();
            socket.setSendBufferSize(4096);
            
            byte[] buffer = new byte[PACKET_SIZE];
            ByteArrayOutputStream baos = new ByteArrayOutputStream(PACKET_SIZE + 4);
            DataOutputStream dos = new DataOutputStream(baos);
    
            // Resolve all receiver addresses in advance
            for (int i = 0; i < receivers.length; i++) {
                try {
                    receiverAddrs[i] = InetAddress.getByName(receivers[i].trim());
                    System.out.println("Resolved " + receivers[i] + " to " + receiverAddrs[i]);
                } catch (UnknownHostException e) {
                    System.err.println("Unknown host: " + receivers[i] + ": " + e.getMessage());
                    receiverAddrs[i] = null; 
                }
            }

            while (isRunning) {
                // Read audio data
                int bytesRead = line.read(buffer, 0, buffer.length);
                if (bytesRead <= 0) continue;
                
                // Add sequence number to the packet
                baos.reset();
                dos.writeInt(sequenceNumber);
                baos.write(buffer, 0, bytesRead);
                byte[] packetData = baos.toByteArray();
                
                // Send to each valid receiver
                for (int i = 0; i < receivers.length; i++) {
                    String receiver = receivers[i].trim();
                    if (receiverAddrs[i] == null || receiver.equals(myIP)) continue; // Skip invalid addresses
                    try {
                        DatagramPacket packet = new DatagramPacket(
                        packetData, packetData.length, receiverAddrs[i], clients.get(receiver));
                        socket.send(packet);
                    } catch (IOException e) {
                        System.err.println("Error sending to " + receivers[i] + ": " + e.getMessage());
                        // Try to reconnect on error
                        try {
                            receiverAddrs[i] = InetAddress.getByName(receivers[i].trim());
                        } catch (UnknownHostException uhe) {
                            receiverAddrs[i] = null;
                        }
                    }
                }
                
                sequenceNumber++; // Increment sequence number after sending to all receivers
            }
        } catch (Exception e) {
            System.err.println("Error in AudioSender group call: " + e.getMessage());
            e.printStackTrace();
        } finally {
            cleanUp();
        }
    }

    /**
     * Returns the audio format for the audio data sent in the private or group call.
     * The format is 16-bit PCM mono at 16kHz, with signed samples and little-endian byte order.
     * @return An AudioFormat instance representing the audio format
     */
    private AudioFormat getAudioFormat() {
        return new AudioFormat(16000.0f, 16, 1, true, false);
    }

    /**
     * Stops the audio sender and closes all open resources.
     * This method is idempotent; it can be called multiple times without
     * any adverse effects.
     */
    public void stop() {
        isRunning = false;
        cleanUp();
    }

    /**
     * Cleans up resources used by the AudioSender.
     * Stops and closes the audio line if it is open, and closes the socket
     * if it is not already closed. Logs errors encountered during cleanup.
     */
    private void cleanUp() {
        if (line != null) {
            try {
                line.stop();
                line.close();
            } catch (Exception e) {
                System.err.println("Error closing audio line: " + e.getMessage());
            }
        }
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (Exception e) {
                System.err.println("Error closing socket: " + e.getMessage());
            }
        }
    }
}