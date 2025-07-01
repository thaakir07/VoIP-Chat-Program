import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/*
 * Client class for the Chat Application. 
 * This class represents a client that connects to a server and sends and receives messages.
 * It also implements the main entry point for the JavaFX application and handles the user interface.
 */
public class Client {
    private Socket socket;
    private BufferedWriter output;
    private BufferedReader input;
    private String username;
    private String myIP;
    private String[] users;
    private Scanner scanner;
    private Thread senderThread;
    private Thread receiverThread;
    private AudioSender audioSender;
    private AudioReceiver audioReceiver;
    private boolean inCall = false;
    private String[] callee = new String[2];

    private VoiceNotes voiceNotes;
    private File voiceNote;
    private boolean isRecording = false;
    private static File receivedDirectory;
    private static final int VOICE_NOTE_PORT = 9876;

    private HashMap<String, Set<String>> groups = new HashMap<>(); // Store group name and members


    /*
     * Constructor for the Client class.
     * @param socket the socket to connect to the server
     * @param username the username of the client
     */
    public Client() {
        this.scanner = new Scanner(System.in);
        try {
            this.socket = new Socket("10.242.69.49", 1234);
            this.input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            this.output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            
            //Create a directory to store voice notes
            receivedDirectory = new File("received_voice_notes");
            if (!receivedDirectory.exists()) {
                receivedDirectory.mkdirs();
            }
            //Delete the directory when the application exits
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Cleaning up voice notes directory: " + receivedDirectory.getAbsolutePath());
                deleteDirectory(receivedDirectory);
            }));
            validateUsername();
            //Start listening for voice notes, might get moved to the main function for deon
            startVNReceiverThread();

        } catch (IOException e) {
            System.out.println("Unable to connect to the server.");
            close();
        }
    }

    private void validateUsername() throws IOException {
        while (true) {
            System.out.print("Enter your username: ");
            this.username = scanner.nextLine();
            System.out.print("Enter your IP: ");
            communicate(username);
            this.myIP = scanner.nextLine();
            communicate(myIP);
            String response = input.readLine();
            if ("Username accepted.".equals(response)) {
                System.out.println("You have joined the chat.");
                //Create a VoiceNotes object
                this.voiceNotes = new VoiceNotes(username);
                break;
            } else {
                System.out.println(response);
            }
        }
    }

    public void startMessaging() {
        while (isConnectionActive()) {
            try {
                String message = scanner.nextLine();
                
                // Check for call management commands
                if (message.startsWith("/hangup") && inCall) {
                    communicate("CALL ENDED:" + " " + message.substring("/hangup ".length()));
                    endCurrentCall();
                    continue;
                } else if (message.startsWith("/record")) { 
                    recordVoiceNote();
                    continue;
                } else if (message.startsWith("/listreceived")) {
                    listReceivedVoiceNotes();
                    continue;
                } else if (message.startsWith("/playreceived")) {
                    playReceivedVoiceNote();
                    continue;
                } else if (message.startsWith("/stop")) {
                    if (isRecording) {
                        String receiver = message.substring("/stop ".length());
                        System.out.println(receiver);
                        stopRecording(receiver);
                    } else if (message.startsWith("/stopgroup")) {
                        if (isRecording) {
                            String groupName = message.substring("/stopgroup ".length());
                            voiceNote = voiceNotes.stopRecording();
                            isRecording = false;
                            
                            if (voiceNote != null) {
                                System.out.println("Voice note recorded: " + voiceNote.getName());
                                communicate("Send group vn to: " + groupName);
                            } else {
                                System.out.println("Voice note recording failed or was empty.");
                            }
                        }
                    } else {
                        System.out.println("You are not currently recording a voice note.");
                    }
                    continue;
                } else if (message.startsWith("Call")) {
                    callee[0] = callee[1];
                    callee[1] = message.substring("Call ".length());
                }
                output.write(message);
                output.newLine();
                output.flush();
            } catch (IOException e) {
                System.out.println("Error sending message: " + e.getMessage());
                close();
                break;
            }
        }
    }
    
    /**
     * Starts a new thread that listens for incoming messages from the server.
     * The thread blocks on the readLine() call until a message is received.
     * The different responses from the server are handled differently.
     * If the message is "ONLINE:", the client list is updated.
     * If the message is "LEAVING", the client is removed from the list.
     * If the message is a whisper, the whisper is displayed in the whisper window.
     * If the message is "terminate", the connection is closed and the program exits.
     * If the message is anything else, it is appended to the global chat text area in the GUI.
     */
    public void listenForMessages() {
        // Running the message listener in a separate thread avoids any potential blockages
        new Thread(() -> { 
            String msg;
            //String actualMessage;
            while (isConnectionActive()) {
                try {
                    msg = input.readLine();
                    //Different reactions to the different responses from the Server/ClientHandler
                    if (msg == null) {
                        System.out.println("Oops! Something went wrong, try again later :(");
                        System.exit(0);

                    //Get client list from server
                    } else if (msg.startsWith("ONLINE: ")) {
                        users = msg.substring(7).split(",");

                    } else if (msg.equals("terminate")) {
                        close();
                        System.exit(0);

                    } else if (msg.startsWith("CALL ACCEPTED")) {
                        if (msg.contains("private")) {
                            callHandler(msg, true);
                        } else {
                            callHandler(msg, false);
                        }
                        

                    } else if (msg.contains("CALL ENDED")) {
                        endCurrentCall();

                    } else if (msg.startsWith("GROUP_CREATED:")) { //suli
                        createGroup(msg);
                        
                    } else if (msg.startsWith("GROUP_MESSAGE:")) { //suli
                        // Format: GROUP_MESSAGE:groupName:senderName: message
                        groupMsg(msg);
                        
                    } else if (msg.startsWith("GROUP VN IPS:")) {
                        // Format is "GROUP VN IPS:groupName:ip1,ip2,ip3..."
                        groupVN(msg);
                        
                    } else if (msg.startsWith("VN to")) {
                        String[] parts = msg.split(" ");
                        String receiver = parts[2];
                        sendVoiceNote(voiceNote, receiver);
                    
                    } else {
                        System.out.println(msg);
                    }
                } catch (IOException e) {
                    close();
                    break;
                }
            }
        }).start();
    }

    private void callHandler(String msg, boolean isPrivate) {
        if (inCall) {
            System.out.println("Already in a call");
            communicate("CALL ENDED:" + " " + callee[0]);
            endCurrentCall(); // End any existing call before starting a new one
            System.out.println("Ending current call before starting a new one");
            System.out.println("Callee is: " + callee[0]);
            
            callee[0] = callee[1];
        }
        String msgInfo = msg.substring(msg.indexOf(":") + 1, msg.length()).trim();
        Map <String, Integer> ipPortMap = new HashMap<>();
        if (isPrivate) {
                String receiverInfo = msg.substring(msg.indexOf(":") + 1, msg.length()).trim();
                String[] parts = receiverInfo.split(":");
                String receiverIP = parts[0];
                int receiverPort = Integer.parseInt(parts[1]);
                
                
                System.out.println("Starting audio call with " + receiverIP + " on port " + receiverPort);
                
                // For outgoing audio (sending to the other client)
                ipPortMap.put(receiverIP, receiverPort);
                audioSender = new AudioSender(myIP, ipPortMap, isPrivate);
                
                // For incoming audio (listening on different port)
                // The other client will send to this port
                int myListeningPort = (receiverPort == 5001) ? 5002 : 5001;
                audioReceiver = new AudioReceiver(myListeningPort, isPrivate);
                
                senderThread = new Thread(audioSender);
                receiverThread = new Thread(audioReceiver);
                senderThread.start();
                receiverThread.start();
                
                inCall = true;
                
        } else {
            String[] pairs = msgInfo.split(",");
            for (String pair : pairs) {
                String name = pair.split(":")[0];
                if (name.equals(myIP)) {
                    int myListeningPort = Integer.parseInt(pair.split(":")[1].trim());
                    audioReceiver = new AudioReceiver(myListeningPort, isPrivate);
                } else {
                    String[] temp = pair.split(":");
                    System.out.println(temp[0]);
                    System.out.println(temp[1]);
                    ipPortMap.put(temp[0], Integer.parseInt(temp[1].trim()));
                }
            }

            // For outgoing audio (sending to the other client)
            audioSender = new AudioSender(myIP, ipPortMap, isPrivate);
            
            // For incoming audio (listening on different port)
            // The other client will send to this port
            
            senderThread = new Thread(audioSender);
            receiverThread = new Thread(audioReceiver);
            senderThread.start();
            receiverThread.start();
            
            inCall = true;
        }
    }

    private void endCurrentCall() {
        if (audioSender != null) {
            audioSender.stop();
        }
        if (audioReceiver != null) {
            audioReceiver.stop();
        }
        
        // Allow threads to clean up
        try {
            if (senderThread != null) {
                senderThread.join(1000);
            }
            if (receiverThread != null) {
                receiverThread.join(1000);
            }
        } catch (InterruptedException e) {
            System.err.println("Error while ending call: " + e.getMessage());
        }
        
        audioSender = null;
        audioReceiver = null;
        senderThread = null;
        receiverThread = null;
        inCall = false;
        System.out.println("Call ended");
    }
    
   /**
     * Returns true if the socket is not null, is not closed, and is connected.
     * This is used to check if the connection to the server is active.
     * @return true if the socket is active, false otherwise
     */
    private boolean isConnectionActive() {
        return socket != null && !socket.isClosed() && socket.isConnected();
    }

    /**
     * Closes all open connections and streams associated with this client.
     */
    private void close() {
        try {
            if (input != null) input.close();
            if (output != null) output.close();
            if (socket != null) socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void communicate(String comms) {
        try {
            output.write(comms);
            output.newLine();
            output.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void createGroup(String message) {
        String[] parts = message.split(":", 3);
        if (parts.length == 3) {
            String groupName = parts[1];
            String[] membersArray = parts[2].split(",");
            Set<String> members = new HashSet<>(Arrays.asList(membersArray));
            groups.put(groupName, members);
            System.out.println("Added to group '" + groupName + "' with members: " + String.join(", ", members));
        }
    }

    private void groupMsg(String message) {
        String[] parts = message.split(":", 3);
        if (parts.length == 3) {
            String groupName = parts[1];
            String messageContent = parts[2];
            System.out.println("[" + groupName + "] " + messageContent);
        }
    }

    private void groupVN(String message) {
        String[] parts = message.split(":", 3);
        if (parts.length == 3) {
            String groupName = parts[1];
            String[] ips = parts[2].split(",");
            System.out.println("Sending voice note to " + ips.length + " members of group " + groupName);
            
            for (String ip : ips) {
                if (!ip.trim().isEmpty()) {
                    sendVoiceNote(voiceNote, ip.trim());
                    System.out.println("Voice note sent to " + ip.trim());
                }
            }
            System.out.println("Voice note sent to all members of group " + groupName);
        }
    }

    private void recordVoiceNote() {
        System.out.println("Recording voice note... Type /stop to finish recording.");
        if (voiceNotes.startRecording()) {
            isRecording = true;
        } else {
            System.out.println("Failed to start recording.");
        }
    }

    private void stopRecording(String receiver) {
        voiceNote = voiceNotes.stopRecording();
        isRecording = false;
        
        if (voiceNote != null) {
            System.out.println("Voice note recorded: " + voiceNote.getName());
            communicate("Send vn to: " + receiver);
            //sendVoiceNote(recordedNote);
        } else {
            System.out.println("Voice note recording failed or was empty.");
        }
    }

    private void sendVoiceNote(File voiceNote, String receiverIP) {
        // For testing, we'll send to localhost
        boolean success = VoiceNoteTransfer.sendVoiceNote(receiverIP, VOICE_NOTE_PORT, voiceNote, this.username);
        if (success) {
            System.out.println("Voice note sent successfully!");
        } else {
            System.out.println("Failed to send voice note.");
        }

    }

    private void listReceivedVoiceNotes() {
        File[] files = receivedDirectory.listFiles((dir, name) -> name.endsWith(".wav"));
        
        if (files == null || files.length == 0) {
            System.out.println("No received voice notes available.");
            return;
        }
        
        System.out.println("\nReceived voice notes:");
        for (int i = 0; i < files.length; i++) {
            System.out.println((i + 1) + ". " + files[i].getName());
        }
    }

    private void playReceivedVoiceNote() {
        File[] files = receivedDirectory.listFiles((dir, name) -> name.endsWith(".wav"));
        
        if (files == null || files.length == 0) {
            System.out.println("No received voice notes available.");
            return;
        }
        
        System.out.println("\nReceived voice notes:");
        for (int i = 0; i < files.length; i++) {
            System.out.println((i + 1) + ". " + files[i].getName());
        }
        
        System.out.print("Enter the number of the note to play: ");
        try {
            int fileIndex = Integer.parseInt(scanner.nextLine().trim()) - 1;
            if (fileIndex >= 0 && fileIndex < files.length) {
                playVoiceNote(voiceNotes, files[fileIndex]);
            } else {
                System.out.println("Invalid selection.");
            }
        } catch (NumberFormatException e) {
            System.out.println("Please enter a valid number.");
        }
    }
    
    private void playVoiceNote(VoiceNotes voiceNotes, File noteFile) {
        // Create a countdown latch to track playback completion
        CountDownLatch playbackLatch = new CountDownLatch(1);
        
        // Create a listener thread that will wait for playback to end
        Thread monitorThread = new Thread(() -> {
            try {
                System.out.println("Playing: " + noteFile.getName());
                System.out.println("Will auto-return to menu when complete.");
                
                // Poll the isPlaying status until playback ends
                while (voiceNotes.isPlaying()) {
                    Thread.sleep(200); // Check every 200ms
                }
                
                System.out.println("Playback completed.");
                playbackLatch.countDown(); // Signal that playback is done
            } catch (Exception e) {
                System.err.println("Playback error: " + e.getMessage());
                playbackLatch.countDown(); // Ensure we don't hang on error
            }
        });
        
        monitorThread.start();
        
        // Wait for playback to complete with a timeout
        try {
            // Wait up to 60 seconds for playback to finish
            if (!playbackLatch.await(60, TimeUnit.SECONDS)) {
                System.out.println("Playback timed out.");
            }
        } catch (InterruptedException e) {
            System.err.println("Wait interrupted: " + e.getMessage());
        }
    }
    
    private void startVNReceiverThread() {
        Thread receiverThread = new Thread(() -> {
            try {
                ServerSocket serverSocket = new java.net.ServerSocket(VOICE_NOTE_PORT);
                System.out.println("Voice note receiver started on port " + VOICE_NOTE_PORT);
                
                while (true) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        System.out.println("Voice note connection accepted from: " + clientSocket.getInetAddress());
                        
                        // Handle the received voice note in a separate thread
                        new Thread(() -> {
                            try {
                                File receivedFile = VoiceNoteTransfer.receiveVoiceNote(clientSocket);
                                
                                if (receivedFile != null) {
                                    // Copy to our received directory with a unique name
                                    File savedFile = new File(receivedDirectory, "received_" + System.currentTimeMillis() + ".wav");
                                    java.nio.file.Files.copy(receivedFile.toPath(), savedFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                    System.out.println("\nReceived a voice note: " + savedFile.getName());
                                }
                            } catch (Exception e) {
                                System.err.println("Error handling received voice note: " + e.getMessage());
                            }
                        }).start();
                    } catch (Exception e) {
                        System.err.println("Error accepting voice note connection: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                System.err.println("Voice note receiver error: " + e.getMessage());
            }
        });
        
        receiverThread.setDaemon(true); // Make it a daemon thread so it doesn't prevent JVM exit
        receiverThread.start();
    }

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

    public static void main(String[] args) {
        Client client = new Client(); // No arguments needed
        // try {
        //     client.validateUsername();
        // } catch (IOException e) {
        //     e.printStackTrace();
        // }
        client.listenForMessages();
        client.startMessaging();
    }
}
