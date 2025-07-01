import javax.sound.sampled.*;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This class handles the recording, storage, and playback of voice notes in the VoIP application.
 * Voice notes are recorded using the system microphone, saved to a visible temporary directory,
 * and the directory is deleted when the program exits.
 */
public class VoiceNotes {
    private static final AudioFormat FORMAT = new AudioFormat(8000.0f, 16, 1, true, false);
    private AtomicBoolean isRecording = new AtomicBoolean(false);
    private AtomicBoolean isPlaying = new AtomicBoolean(false);
    private String currentUsername;
    private File recordingFile;
    private List<File> savedNotes = new ArrayList<>();
    private Thread recordingThread;
    private TargetDataLine recordingLine;
    private File voiceNotesDirectory;
    
    public VoiceNotes(String username) {
        this.currentUsername = username;
        
        // Create a visible temporary directory in the current working directory
        String dirName = username + "'s_voice_notes";
        voiceNotesDirectory = new File(dirName);
        voiceNotesDirectory.mkdirs();
        
        // Register a shutdown hook to delete the directory when the JVM exits
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            deleteDirectory(voiceNotesDirectory);
        }));
    }
    
    /**
     * Recursively deletes a directory and all its contents.
     * 
     * @param directory The directory to delete
     * @return true if deletion was successful
     */
    private boolean deleteDirectory(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            return directory.delete();
        }
        return false;
    }

    /**
     * Starts recording a voice note using the system microphone.
     * Uses buffer reading to allow proper stopping of recording on demand.
     * 
     * @return true if recording started successfully, false otherwise
     */
    public boolean startRecording() {
        if (isRecording.get()) {
            stopRecording();
        }
        
        try {
            // Create a file in the visible temporary directory
            recordingFile = new File(voiceNotesDirectory, 
                            "voice_note_" + currentUsername + "_" + 
                            System.currentTimeMillis() + ".wav");
            
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
            if (!AudioSystem.isLineSupported(info)) {
                return false;
            }
            
            recordingLine = (TargetDataLine) AudioSystem.getLine(info);
            recordingLine.open(FORMAT);
            recordingLine.start();
            
            isRecording.set(true);
            
            recordingThread = new Thread(() -> {
                try (ByteArrayOutputStream byteOutputStream = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[recordingLine.getBufferSize()];
                    int bytesRead;
                    
                    while (isRecording.get() && recordingLine.isOpen()) {
                        //reads from mic buffer
                        bytesRead = recordingLine.read(buffer, 0, buffer.length);
                        if (bytesRead > 0) {
                            //Writes raw audio into a ByteArrayOutputStream
                            byteOutputStream.write(buffer, 0, bytesRead);
                        }
                    }
                    
                    byte[] audioData = byteOutputStream.toByteArray();
                    try (AudioInputStream audioInputStream = new AudioInputStream(
                            new ByteArrayInputStream(audioData), 
                            FORMAT, 
                            audioData.length / FORMAT.getFrameSize())) {
                        //writes to .wav file
                        AudioSystem.write(audioInputStream, AudioFileFormat.Type.WAVE, recordingFile);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    // close everything
                    if (recordingLine != null) {
                        recordingLine.stop();
                        recordingLine.close();
                        recordingLine = null;
                    }
                }
            });
            
            recordingThread.start();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Stops the current recording session.
     * Sets the isRecording flag to false and cleans up recording resources.
     * 
     * @return The file containing the recorded audio, or null if no recording was in progress
     */
    public File stopRecording() {
        if (!isRecording.get() || recordingThread == null) {
            return null;
        }
        
        isRecording.set(false);
        
        try {
            recordingThread.join(2000);
        } catch (InterruptedException e) {
        }
        
        if (recordingLine != null) {
            recordingLine.stop();
            recordingLine.close();
            recordingLine = null;
        }
        
        if (recordingFile != null && recordingFile.exists() && recordingFile.length() > 0) {
            savedNotes.add(recordingFile);
            return recordingFile;
        } else {
            return null;
        }
    }
    
    /**
     * Checks if a voice note is currently playing.
     * 
     * @return true if a voice note is currently playing, false otherwise
     */
    public boolean isPlaying() {
        return isPlaying.get();
    }
    
    /**
     * Stops the current playback if one is in progress.
     * 
     * @return true if playback was stopped, false if no playback was in progress
     */
    
    /**
     * Adds a received voice note to the collection.
     * 
     * @param file The received voice note file
     * @return true if the file was added successfully
     */
    public boolean addReceivedVoiceNote(File file) {
        if (file != null && file.exists()) {
            savedNotes.add(file);
            return true;
        }
        return false;
    }
    
    /**
     * Gets the list of saved voice notes.
     * 
     * @return A list of voice note files
     */
    public List<File> getSavedNotes() {
        return new ArrayList<>(savedNotes);
    }
}
