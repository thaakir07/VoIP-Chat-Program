import javax.sound.sampled.*;

import java.net.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.io.*;

/**
 * This class handles receiving and playing audio data for private and group voice
 * calls using UDP.
*/
public class AudioReceiver implements Runnable {
    private int port;
    private boolean isPrivate;
    private DatagramSocket socket = null;
    private SourceDataLine speaker = null;
    private volatile boolean isRunning = true;

    // A jitter buffer to smooth out audio
    private TreeMap<Integer, byte[]> jitterBuffer = new TreeMap<>();  // private calls
    private int expectedSeq = 0;

    // jitter window and packet size
    private static final int PACKET_SIZE = 320; 

    /**
     * Constructor for AudioReceiver.
     * 
     * @param port The port number to use for receiving audio data.
     * @param isPrivate True if this is a private call, false if it's a group call.
     */
    public AudioReceiver(int port, boolean isPrivate) {
        this.port = port;
        this.isPrivate = isPrivate;
    }

    /**
     * Runs the audio receiver.
     * Depending on the value of isPrivate, either a private call or a group call
     * is received.
     */
    @Override
    public void run() {
        if (isPrivate) {
            receivePrivateCall();
        } else {
            receiveGroupCall();
        }   
    }

    /**
     * Receive a private call and play the audio.
     * Uses a jitter buffer to smooth out audio. The jitter buffer is a tree map with
     * sequence number of the packet as the key and the audio data is the value. The
     * program plays the packets in order as they are received. The tree map handles
     * out of order packets by automatically ordering them based on their sequence
     * number. If the socket times out, the program will play whatever packets did 
     * arrive before the timeout. If the buffer is empty, it will play a small amount of
     * silence.
     */
    public void receivePrivateCall() {
        AudioFormat format = getAudioFormat();
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        int jitterBufferSize = 10;
        
        try {
            speaker = (SourceDataLine) AudioSystem.getLine(info);
            speaker.open(format, PACKET_SIZE * 2);
            speaker.start();
    
            socket = new DatagramSocket(port);
            socket.setSoTimeout(10);
            socket.setReceiveBufferSize(4096);
    
            byte[] buffer = new byte[PACKET_SIZE];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            
            while (isRunning) {
                try {
                    socket.receive(packet);
                    ByteArrayInputStream bais = new ByteArrayInputStream(packet.getData(), 0, packet.getLength());
                    DataInputStream dis = new DataInputStream(bais);
                    int seqNum = dis.readInt();
                    byte[] audioData = new byte[packet.getLength() - 4];
                    System.arraycopy(packet.getData(), 4, audioData, 0, audioData.length);
                    
                    // add to the jitter buffer tree map (sequence number as key, audio data as value)
                    synchronized (jitterBuffer) {
                        jitterBuffer.put(seqNum, audioData);
                        
                        while (!jitterBuffer.isEmpty() && jitterBuffer.containsKey(expectedSeq)) {
                            byte[] dataToPlay = jitterBuffer.remove(expectedSeq);
                            speaker.write(dataToPlay, 0, dataToPlay.length);
                            expectedSeq++;
                        }
                        
                        // handle overflow and very old packets
                        while (jitterBuffer.size() > jitterBufferSize) {
                            Integer oldestKey = jitterBuffer.firstKey();
                            jitterBuffer.remove(oldestKey);
                        }
                    }  
                } catch (SocketTimeoutException e) {
                    // play whatever packets did arrive before the timeout
                    synchronized (jitterBuffer) {
                        if (!jitterBuffer.isEmpty()) {
                            Integer nextKey = jitterBuffer.firstKey();
                            byte[] dataToPlay = jitterBuffer.remove(nextKey);
                            speaker.write(dataToPlay, 0, dataToPlay.length);
                            expectedSeq = nextKey + 1;
                        } else {
                            // If buffer is empty, play a small amount of silence
                            byte[] silence = new byte[PACKET_SIZE / 4];
                            speaker.write(silence, 0, silence.length);
                        }
                    }
                } catch (IOException e) {
                    if (!isRunning) break;
                    System.err.println("Network error: " + e.getMessage());
                    reconnectSocket();
                }
            }
        } catch (Exception e) {
            System.err.println("AudioReceiver private call error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            cleanUp();
        }
    }

/**
 * Receives and processes audio packets for a group call.
 * This method initializes the audio output line and a datagram socket for receiving audio
 * packets. It uses a jitter buffer for each sender to handle packet ordering and loss. For
 * each packet received, it extracts the audio data and sequence number, and stores them in
 * the jitter buffer for the corresponding sender. It continuously processes and plays audio
 * from the buffers. If the buffer overflows, the oldest packets are discarded. When a timeout
 * occurs or an IOException is caught, it processes any available audio data.
 */
    public void receiveGroupCall() {
        AudioFormat format = getAudioFormat();
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        int jitterBufferSize = 5;
        try {
            speaker = (SourceDataLine) AudioSystem.getLine(info);
            speaker.open(format, PACKET_SIZE * 2);
            speaker.start();
            
            socket = new DatagramSocket(port);
            socket.setSoTimeout(10);
            socket.setReceiveBufferSize(4096);
            
            byte[] buffer = new byte[PACKET_SIZE + 8];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            
            // jitter buffers for each sender. TreeMap for each sender to order audio data by sequence number
            Map<String, TreeMap<Integer, byte[]>> jitterBuffers = new HashMap<>();
            Map<String, Integer> expectedSeqNumbers = new HashMap<>();
            
            while (isRunning) {
                try {
                    socket.receive(packet);
                    ByteArrayInputStream bais = new ByteArrayInputStream(packet.getData(), 0, packet.getLength());
                    DataInputStream dis = new DataInputStream(bais);
                    byte[] audioData;
                    int seqNum = 0;

                    // get each senders address as string to use as key
                    String senderAddress = packet.getAddress().getHostAddress();
                    
                    if (packet.getLength() > PACKET_SIZE) {
                        try {
                            seqNum = dis.readInt();
                            audioData = new byte[packet.getLength() - 4];
                            System.arraycopy(packet.getData(), 4, audioData, 0, audioData.length);
                        } catch (Exception e) {
                            // if sequence numbers are not sent
                            audioData = new byte[packet.getLength()];
                            System.arraycopy(packet.getData(), 0, audioData, 0, packet.getLength());
                        }
                    } else {
                        audioData = new byte[packet.getLength()];
                        System.arraycopy(packet.getData(), 0, audioData, 0, packet.getLength());
                    }
                    
                    // add audio data to for the respective sender
                    synchronized (jitterBuffers) {
                        if (!jitterBuffers.containsKey(senderAddress)) {
                            jitterBuffers.put(senderAddress, new TreeMap<>());
                            expectedSeqNumbers.put(senderAddress, 0);
                        }
                        
                        TreeMap<Integer, byte[]> buff = jitterBuffers.get(senderAddress);
                        buff.put(seqNum, audioData);
                        
                        // handle overflow of the jitter buffer
                        while (buff.size() > jitterBufferSize) {
                            buff.remove(buff.firstKey());
                        }
                    }
                    processAndPlayAudio(jitterBuffers, expectedSeqNumbers);

                } catch (SocketTimeoutException e) {
                    processAndPlayAudio(jitterBuffers, expectedSeqNumbers);
                } catch (IOException e) {
                    if (!isRunning) break;
                    System.err.println("Network error in group call: " + e.getMessage());
                    reconnectSocket();
                }
            }
        } catch (Exception e) {
            System.err.println("AudioReceiver group call error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            cleanUp();
        }
    }
    
/**
 * Processes and plays audio data from multiple senders using jitter buffers. Retrieves the 
 * audio packets from the jitter buffers of each sender, ensuring the correct order using
 * sequence numbers. If the expected packet is missing, the oldest available packet is used instead.
 * The audio data from all senders is then mixed and played through the audio output line.
 *
 * @param senderBuffers A map containing the jitter buffers for each sender, 
 * where the key is the sender's address and the value is a TreeMap of sequence numbers
 * to audio byte arrays.
 * 
 * @param expectedSeqNumbers A map of the expected sequence number for each sender,
 * used to determine the order of playing the audio packet.
 */
    private void processAndPlayAudio(Map<String, TreeMap<Integer, byte[]>> senderBuffers, 
                                   Map<String, Integer> expectedSeqNumbers) {
        List<byte[]> audioToMix = new ArrayList<>();
        
        synchronized (senderBuffers) {
            for (String sender : senderBuffers.keySet()) {
                TreeMap<Integer, byte[]> buffer = senderBuffers.get(sender);
                int expected = expectedSeqNumbers.get(sender);
                
                if (!buffer.isEmpty()) {
                    byte[] dataToPlay;

                    if (buffer.containsKey(expected)) {
                        dataToPlay = buffer.remove(expected);
                        expectedSeqNumbers.put(sender, expected + 1);
                    } else if (!buffer.isEmpty()) {
                        // otherwise use the oldest packet
                        int oldestKey = buffer.firstKey();
                        dataToPlay = buffer.remove(oldestKey);
                        expectedSeqNumbers.put(sender, oldestKey + 1);
                    } else {
                        continue;
                    }
                    
                    if (dataToPlay.length > 0) {
                        audioToMix.add(dataToPlay);
                    }
                }
            }
        }
        
        if (!audioToMix.isEmpty()) {
            byte[] mixedAudio = mixAudioSamples(audioToMix, getAudioFormat());
            speaker.write(mixedAudio, 0, mixedAudio.length);
        } else {
            // if no audio, play small silence
            byte[] silence = new byte[PACKET_SIZE / 4];
            speaker.write(silence, 0, silence.length);
        }
    }

/**
 * Mixes multiple audio samples into a single audio stream.
 * This method takes a list of audio byte arrays and combines them into a single byte array and
 * then normalises the combined audio to prevent clipping.
 * The function first converts byte pairs into 16-bit signed samples and then averages
 * the samples from all input arrays. Finally, it converts the mixed samples back to into bytes. 
 * 
 * @param audioToMix A list of byte arrays representing audio samples to be mixed together.
 * @param format The audio format of the samples.
 * @return A byte array containing the mixed audio samples.
 */
    public byte[] mixAudioSamples(List<byte[]> audioToMix, AudioFormat format) {
        if (audioToMix.isEmpty()) {
            return new byte[0];
        }

        double normalizationFactor = 0.7 / audioToMix.size();
        byte[] mixed = new byte[PACKET_SIZE];
        short[][] samples = new short[audioToMix.size()][PACKET_SIZE/2];
        
        // combine 2 bytes into signed 16bit samples
        for (int c = 0; c < audioToMix.size(); c++) {
            byte[] chunk = audioToMix.get(c);
            for (int i = 0, j = 0; i < PACKET_SIZE && i < chunk.length - 1; i += 2, j++) {
                // use bitwise OR to combine the bytes to form a 16-bit sample
                samples[c][j] = (short) ((chunk[i + 1] << 8) | (chunk[i] & 0xFF));
            }
        }

        for (int i = 0; i < PACKET_SIZE/2; i++) {
            double mixedSample = 0;
            
            // add all sample values for each client
            for (int c = 0; c < audioToMix.size(); c++) {
                mixedSample += samples[c][i];
            }
            
            mixedSample *= normalizationFactor;
            
            // clamp to prevent clipping. use 16bit signed limits
            if (mixedSample > Short.MAX_VALUE) mixedSample = Short.MAX_VALUE;
            if (mixedSample < Short.MIN_VALUE) mixedSample = Short.MIN_VALUE;
            
            // convert back to bytes
            short finalSample = (short)mixedSample;
            mixed[i*2] = (byte) (finalSample & 0xFF);
            mixed[i*2 + 1] = (byte) ((finalSample >> 8) & 0xFF);
        }
        return mixed;
    }
    
    /**
     * Gets the audio format used by the audio receiver.
     * @return The audio format used by the audio receiver.
     */
    public AudioFormat getAudioFormat() {
        return new AudioFormat(16000.0f, 16, 1, true, false);
    }

    /**
     * This method loses the current socket and reconnects to the port. It is called when
     * the socket times out or an IOException is caught. It will close the current socket
     * if it is not already closed, and then create a new socket.
     */
    private void reconnectSocket() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            socket = new DatagramSocket(port);
            socket.setSoTimeout(10);
            socket.setReceiveBufferSize(4096);
        } catch (IOException e) {
            System.err.println("Socket reconnection failed: " + e.getMessage());
        }
    }
    
    /**
     * Stops the audio receiver and cleans up resources. It sets the isRunning flag
     * to false and calls the cleanUp method to close the speaker and socket.
     */
    public void stop() {
        isRunning = false;
        cleanUp();
    }
    
    /**
     * This method cleans up resources used by the audio receiver.
     * it will close the speaker and socket if they are not already closed.
     */
    private void cleanUp() {
        if (speaker != null) {
            try {
                speaker.drain();
                speaker.stop();
                speaker.close();
            } catch (Exception e) {
                System.err.println("Error closing speaker: " + e.getMessage());
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