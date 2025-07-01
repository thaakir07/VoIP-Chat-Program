import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.*;

/**
 * VoiceNoteSender performs automated testing of voice note transmission between machines.
 * Generates synthetic voice notes files of various duration and sends them to VoiceNoteReceiver
 * for the performance. Conducts series of trials,  sending voice notes of different durations
 * multiple times and measuring the transmission time. Results are analyzed and presented.
 */
public class VoiceNoteSender {
    
    private static final int PORT = 9876;
    private static final int NUM_TRIALS = 5;
    private static final int[] DURATIONS_SECONDS = {30, 60, 300, 600};
    
    // IP address of the receiver
    private static final String TARGET_IP = "10.242.69.49";
    
    /**
     * Internal class for storing results of each transmission test.
     * Tracks the duration of the voice note, file size, transmission time,
     * and whether the transmission was successful.
     */
    private static class TransmissionResult {
        int durationSeconds;
        long fileSize;
        long transmissionTimeMs;
        boolean success;
        
        @Override
        public String toString() {
            return String.format("Duration: %ds, Size: %d bytes, Time: %d ms, Success: %s", 
                    durationSeconds, fileSize, transmissionTimeMs, success);
        }
    }
    
    /**
     * The main entry point for the application.
     * Initializes the experiment and conducts trials for each duration.
     */
    public static void main(String[] args) {
        System.out.println("=== Voice Note Transmission Experiment (SENDER) ===");
        System.out.println("Sending voice notes to: " + TARGET_IP + ":" + PORT);
        System.out.println("Make sure the receiver is running first!");
        
        // Wait for confirmation to start
        System.out.println("Press Enter to begin experiment...");
        try {
            System.in.read();
        } catch (IOException e) {
        }
        
        // Create results container
        List<TransmissionResult> results = new ArrayList<>();
        
        try {
            // For each duration, create and send voice notes
            for (int duration : DURATIONS_SECONDS) {
                System.out.println("\nTesting " + duration + " second voice notes...");
                
                // Run multiple trials for statistical significance
                for (int trial = 1; trial <= NUM_TRIALS; trial++) {
                    // Record a voice note of the specified duration
                    File voiceNoteFile = recordVoiceNote(duration);
                    
                    if (voiceNoteFile != null) {
                        // Get file size
                        long fileSize = voiceNoteFile.length();
                        
                        // Measure transmission
                        TransmissionResult result = new TransmissionResult();
                        result.durationSeconds = duration;
                        result.fileSize = fileSize;
                        
                        // Send the voice note and measure time
                        long startTime = System.currentTimeMillis();
                        boolean success = VoiceNoteTransfer.sendVoiceNote(TARGET_IP, PORT, voiceNoteFile, "ExpSender");
                        long endTime = System.currentTimeMillis();
                        
                        // Store results
                        result.transmissionTimeMs = endTime - startTime;
                        result.success = success;
                        
                        results.add(result);
                        
                        System.out.printf("Trial %d: Size: %d bytes, Time: %d ms, Success: %s\n", 
                                trial, fileSize, result.transmissionTimeMs, success);
                        
                        // Small delay between trials
                        Thread.sleep(1000);
                    }
                }
            }
            
            // Print summary of results
            printResults(results);
            
        } catch (Exception e) {
            System.err.println("Experiment failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Creates a synthetic voice note file of the specified duration.
     * The created file contains silence (zero samples) with proper WAV format headers.
     * 
     * @param durationSeconds The duration of the voice note in seconds
     * @return A temporary File object containing the synthetic voice note,
     *         or null if an error occurred
     */
    private static File recordVoiceNote(int durationSeconds) {
        try {
            // Create a silent WAV file of the specified duration
            File outputFile = File.createTempFile("exp_voice_note_" + durationSeconds + "s_", ".wav");
            
            // Use PCM 16-bit mono format at 8000 Hz (same as your VoiceNotes class)
            AudioFormat format = new AudioFormat(8000.0f, 16, 1, true, false);
            
            // Calculate total bytes based on duration, sample rate, and bytes per sample
            int totalBytes = (int)(durationSeconds * format.getSampleRate() * format.getFrameSize());
            
            // Create the WAV file
            try (AudioInputStream ais = new AudioInputStream(
                    new ByteArrayInputStream(new byte[totalBytes]), 
                    format, 
                    totalBytes / format.getFrameSize())) {
                AudioSystem.write(ais, AudioFileFormat.Type.WAVE, outputFile);
            }
            
            return outputFile;
            
        } catch (Exception e) {
            System.err.println("Error creating voice note: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * Analyzes and prints a summary of the experiment results.
     * Groups results by duration and calculates average transmission time.
     * 
     * @param results The list of TransmissionResult objects to analyze
     */
    private static void printResults(List<TransmissionResult> results) {
        System.out.println("\n=== EXPERIMENT RESULTS ===");
        System.out.println("Duration (s) | Avg Time (ms)");
        System.out.println("-------------|-------------");
        
        // Group results by duration
        for (int duration : DURATIONS_SECONDS) {
            List<TransmissionResult> durationResults = new ArrayList<>();
            
            for (TransmissionResult result : results) {
                if (result.durationSeconds == duration) {
                    durationResults.add(result);
                }
            }
            
            if (!durationResults.isEmpty()) {
                // Calculate average time
                long totalTime = 0;
                
                for (TransmissionResult result : durationResults) {
                    totalTime += result.transmissionTimeMs;
                }
                
                long avgTime = totalTime / durationResults.size();
                
                System.out.printf("%-13d | %-13d\n", duration, avgTime);
            }
        }
    }
}
