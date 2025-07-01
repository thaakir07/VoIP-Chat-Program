import java.io.*;
import java.net.*;

/**
 * Utility class for transferring voice note files between clients via TCP.
 */
public class VoiceNoteTransfer {
    
    /**
     * Sends a voice note file to a recipient via TCP.
     * 
     * @param recipientIp The IP address of the recipient
     * @param port The port to connect to
     * @param voiceNoteFile The voice note file to send
     * @param senderUsername The username of the sender (can be null if not needed)
     * @return true if sent successfully, false otherwise
     */
    public static boolean sendVoiceNote(String recipientIp, int port, File voiceNoteFile, String senderUsername) {
        try (Socket socket = new Socket(recipientIp, port);
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             FileInputStream fis = new FileInputStream(voiceNoteFile)) {
            
            // Send metadata if sender username is provided
            if (senderUsername != null) {
                dos.writeBoolean(true); // Indicates username is included
                dos.writeUTF(senderUsername);
            } else {
                dos.writeBoolean(false); // Indicates no username
            }
            
            // Send file size
            dos.writeLong(voiceNoteFile.length());
            
            // Send file data
            byte[] buffer = new byte[4096];
            int bytesRead;
            
            while ((bytesRead = fis.read(buffer)) != -1) {
                dos.write(buffer, 0, bytesRead);
            }
            
            dos.flush();
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Receives a voice note file via an established socket connection.
     * 
     * @param socket The socket connected to the sender
     * @return The received file, or null if reception failed
     */
    public static File receiveVoiceNote(Socket socket) {
        try (DataInputStream dis = new DataInputStream(socket.getInputStream())) {
            // Read metadata
            boolean hasUsername = dis.readBoolean();
            String sender = hasUsername ? dis.readUTF() : "unknown";
            long fileSize = dis.readLong();
            
            // Create temporary file
            File receivedFile = File.createTempFile("received_note_" + sender + "_", ".wav");
            receivedFile.deleteOnExit();
            
            // Read file data
            try (FileOutputStream fos = new FileOutputStream(receivedFile)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                long totalBytesRead = 0;
                
                while (totalBytesRead < fileSize) {
                    bytesRead = dis.read(buffer, 0, (int) Math.min(buffer.length, fileSize - totalBytesRead));
                    if (bytesRead == -1) {
                        break;
                    }
                    
                    fos.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;
                }
            }
            
            return receivedFile;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * Convenience method for sending a voice note without specifying a sender username.
     * Useful for direct client-to-client communication where usernames are already known.
     * 
     * @param recipientIp The IP address of the recipient
     * @param port The port to connect to
     * @param voiceNoteFile The voice note file to send
     * @return true if sent successfully, false otherwise
     */
    public static boolean sendVoiceNoteToClient(String recipientIp, int port, File voiceNoteFile) {
        return sendVoiceNote(recipientIp, port, voiceNoteFile, null);
    }
}
