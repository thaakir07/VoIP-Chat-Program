import java.io.*;
import java.net.*;

/**
 * VoiceNoteReceiver - Keeps listening for voice notes and receives them over the network.
 * Used with VoiceNoteSender for testing voice note transmission performance between machines.
 * Continuously listens for incoming connection.
 * Receives audio files and saves them.
 */
public class VoiceNoteReceiver {
    
    private static final int PORT = 9876;
    
    /**
     * The main entry point for the application.
     * Initializes the server, creates a directory for received files, and
     * listens for incoming connections in an infinite loop.
     */
    public static void main(String[] args) {
        System.out.println("=== Voice Note Transmission Experiment (RECEIVER) ===");
        System.out.println("Listening for voice notes on port " + PORT);
        
        // Creates directory for receiving voice notes.
        File receivedDir = new File("experiment_received");
        receivedDir.mkdirs();
        
        try {
            ServerSocket serverSocket = new ServerSocket(PORT);
            int fileCount = 0;
            long totalBytesReceived = 0;
            
            System.out.println("Receiver started. Waiting for files...");
            
            // Ifinite loop to keep listening for voice notes.
            while (true) {
                try {
                    Socket socket = serverSocket.accept();
                    System.out.println("Connection from: " + socket.getInetAddress().getHostAddress());
                    
                    // Handle received voice note
                    File receivedFile = VoiceNoteTransfer.receiveVoiceNote(socket);
                    
                    if (receivedFile != null) {
                        fileCount++;
                        totalBytesReceived += receivedFile.length();
                        
                        // Save to experiment directory with a unique name
                        File savedFile = new File(receivedDir, 
                                "received_" + fileCount + "_" + System.currentTimeMillis() + ".wav");
                        
                        // Copy the file
                        try (FileInputStream fis = new FileInputStream(receivedFile);
                             FileOutputStream fos = new FileOutputStream(savedFile)) {
                            
                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            
                            while ((bytesRead = fis.read(buffer)) != -1) {
                                fos.write(buffer, 0, bytesRead);
                            }
                        }
                        
                        System.out.println("Received file #" + fileCount + ": " + 
                                receivedFile.length() + " bytes");
                        System.out.println("Total received: " + fileCount + " files, " + 
                                totalBytesReceived + " bytes");
                    }
                } catch (Exception e) {
                    System.err.println("Error receiving file: " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            System.err.println("Receiver failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
