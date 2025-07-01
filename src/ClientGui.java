// Backend imports
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Scanner;

// GUI imports
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.Scene;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

/**
 * This class provides the GUI for the Client side of the VoIP application.
 * Handles the Log in of the user, then
 * Starts the 2 listening threads for messages and voice notes
 * Displays a GUI for the user allowing sending of Global, group and private messages
 * Allows for the interaction of calling privately or globally as well as sending voice notes via buttons and event handlers
 */

public class ClientGui extends Application{
    // User details
    private String[] userInfo, users;
    private String[] callee = new String[2];
    private String username = "Enter username", myIp = "localhost";
    private Socket socket;
    private BufferedWriter output;
    private BufferedReader input;
    private Scanner scanner;

    // Hashmaps to store user backend and GUI info
    private HashMap<String, Integer> activeClientMap = new HashMap<String, Integer>();
    private ConcurrentHashMap<String, VBox> openWispWindows = new ConcurrentHashMap<String, VBox>();
    private HashMap<String, VBox> openGroups = new HashMap<String, VBox>();
    private HashMap<String, HashMap<String, String>> myGroups = new HashMap<String, HashMap<String, String>>();
    //private ConcurrentHashMap<String, Button> privateCallButtons = new ConcurrentHashMap<String, Button>();

    // GUI global components
    private VBox wispBox, groupsBox;
    private TextArea globalChatOutput;
    private ListView<String> activeClientList = new ListView<String>();

    // Call and Voice note related global vars
    private File curSelectedFile = null, receivedDirectory;
    private Clip curSoundClip = null;
    private VoiceNotes voiceNote;
    private AtomicBoolean isRecording = new AtomicBoolean(false);
    private Boolean inCall = false;
    private final int VN_PORT = 9786;
    private AudioSender audioSender;
    private AudioReceiver audioReceiver;
    private Thread senderThread;
    private Thread receiverThread;

    
    /** 
     * Constructor for the ClientGui class
     * Sets up the socket connection to the server 
     * as well as the input and output streams to communicate with the server
     */
    public ClientGui() {
        this.scanner = new Scanner(System.in);
        try {
            this.socket = new Socket("10.242.69.49", 1235);
            this.input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            this.output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
        } catch (IOException e) {
            System.out.println("Unable to connect to the server.");
            close();
            System.exit(0);
        }
    }

    
    /**
     * Main method point for the JavaFX application.
     * This method sets up the GUI components, starts the listen for messages thread,
     * and starts listening for incoming voice notes from other clients.
     * @param mainStage The main stage of the application
     */
    public void start(Stage mainStage) {
        logInUser();

        // Start listening for messages here to get active users
        Thread listenerThread = new Thread(() -> listenForMessages());
        listenerThread.setDaemon(true);
        listenerThread.start();

        // Add a listen for voice notes thread
        Thread listenForNotesThread = new Thread(() -> listenForVoiceNotes());
        listenForNotesThread.setDaemon(true);
        listenForNotesThread.start();

        // Build the GUI components but dont show yet
        Platform.runLater(() -> {
            groupsBox = buildGroupsBox();
            wispBox = buildWispBox();
            VBox clientBox = buildClientBox();
            VBox extrasBox = buildExtras(clientBox, groupsBox, mainStage);
            VBox rightBox = new VBox(clientBox, extrasBox);
            rightBox.setStyle("-fx-background-color: rgb(40, 45, 50);");

            BorderPane backgroundPane = buildBackground(groupsBox, wispBox, rightBox);
            Scene scene = new Scene(backgroundPane, 1200, 700);
            mainStage.setTitle("Chillax Client");
            mainStage.setScene(scene);
            mainStage.show();
        });
    }

    /*################################################################
    * ####################### BACKEND METHODS ########################
    * ################################################################*/

    /**
     * Starts a new thread that listens for incoming messages from the server.
     * The thread blocks on the readLine() call until a message is received.
     * Then based on what the message starts with diffrent actions are performed
     */
    public void listenForMessages() {
        String msg;
        while (isConnectionActive()) {
            try {
                msg = input.readLine();
                if (msg == null) {
                        System.out.println("Oops! Something went wrong, try again later :(");
                } else if (msg.startsWith("ONLINE:")) {
                    populateClientList(msg);
                } else if (msg.startsWith("LEAVING:")) {
                    removeClientFromList(msg);
                } else if (msg.startsWith("Whisper from")) {
                    receiveIncomingWisp(msg);
                } else if (msg.startsWith("Join Group")) {
                    receiveGroup(msg);
                } else if (msg.startsWith("Group message from")){
                    receiveGroupMessage(msg);
                } else if (msg.startsWith("receivedIPs voicenote:")) {
                    sendVoiceNote(msg);
                } else if (msg.startsWith("CALL ACCEPTED")) {
                    
                    // check if group or private
                    if (msg.contains("private")) {
                        String receiver = msg.substring(msg.lastIndexOf(":") + 1);
                        String newMsg = msg.substring(0, msg.lastIndexOf(":"));
                        if (!username.equals(receiver)) {
                            receiveIncomingWisp("Call from :" + receiver);
                        }
                        callHandler(newMsg, true);
                    } else {
                        callHandler(msg, false);
                        if (msg.contains("global")) {
                            Platform.runLater(() -> {
                                // Get access to everyones global call button
                                VBox globalBox = (VBox) groupsBox.getChildren().get(0);
                                HBox globalInputBox = (HBox) globalBox.getChildren().get(2);
                                Button globalCallButton = (Button) globalInputBox.getChildren().get(2);
                                globalCallButton.setText("Stop Call");
                                globalCallButton.setOnAction(e -> stopCall(globalCallButton, globalChatOutput, "Global"));
                            });
                        }
                    }
                } else if (msg.startsWith("CALL ENDED")) {
                    String sender = msg.substring(msg.lastIndexOf(":") + 1);
                    // If you are not the person that ended the call check your open wisp windows
                    if (!(username.equals(sender)) && openWispWindows.containsKey(sender)) {
                        Platform.runLater(() -> {
                            // Flip button for the person your sending too
                            VBox wispWindow = openWispWindows.get(sender);
                            TextArea wispMessageOut = (TextArea) wispWindow.getChildren().get(0);
                            HBox wispInputBox = (HBox) wispWindow.getChildren().get(1);
                            Button callButton = (Button) wispInputBox.getChildren().get(2);
                            callButton.setText("Start Call");
                            callButton.setOnAction(e -> startCall(callButton, wispMessageOut, sender));
                        });
                    }

                    // Flip their other stop calls back to start calls
                    if (!openWispWindows.isEmpty()) {
                        for (String client: openWispWindows.keySet()) {
                            if (!client.equals(sender)) {
                                VBox notReceiverWispBox = openWispWindows.get(client);
                                TextArea notReceiverWispMessageOut = (TextArea) notReceiverWispBox.getChildren().get(0);
                                HBox notReceiverWispInputBox = (HBox) notReceiverWispBox.getChildren().get(1);
                                Button notReceiverCallButton = (Button) notReceiverWispInputBox.getChildren().get(2);
                                if (notReceiverCallButton.getText().equals("Stop Call")) {
                                    notReceiverCallButton.setText("Start Call");
                                    notReceiverCallButton.setOnAction(e -> startCall(notReceiverCallButton, notReceiverWispMessageOut, sender));
                                }
                            }
                        }
                    }

                    endCurrentCall();
                } else if (msg.startsWith("terminate")) {
                    close();
                    Platform.exit();
                } else {
                    final String finalMsg = msg;
                    Platform.runLater(() -> globalChatOutput.appendText(finalMsg + "\n"));
                }
            } catch (IOException e) {
                close();
                break;
            }
        }
    }

    /**
     * Closes the input and output streams, then close the socket associated with this client.
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

    /**
     * This function is used to check if the connection to the server is active.
     * @return true if the socket is active, false otherwise
     */
    private boolean isConnectionActive() {
        return socket != null && !socket.isClosed() && socket.isConnected();
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
     * Handles call messages from the server, either private or group
     * gets used when a new call has been accepted
     * 
     * @param msg The message received from the server
     * @param isPrivate true if the call is private, false if it is a global call
     */
    private void callHandler(String msg, boolean isPrivate) {
        if (inCall) {
            communicate("CALL ENDED:" + " " + callee[0]);
            endCurrentCall();
            callee[0] = callee[1];
            Platform.runLater(() -> globalChatOutput.appendText("You are already in a call\n"));
        }
        String msgInfo = msg.substring(msg.indexOf(":") + 1, msg.length()).trim();
        Map <String, Integer> ipPortMap = new HashMap<>();
        if (isPrivate) {
                String receiverInfo = msg.substring(msg.indexOf(":") + 1, msg.length()).trim();
                String[] parts = receiverInfo.split(":");
                String receiverIP = parts[0];
                int receiverPort = Integer.parseInt(parts[1]);

                // For outgoing audio (sending to the other client)
                ipPortMap.put(receiverIP, receiverPort);
                audioSender = new AudioSender(myIp, ipPortMap, isPrivate);
                
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
                if (name.equals(myIp)) {
                    int myListeningPort = Integer.parseInt(pair.split(":")[1].trim());
                    audioReceiver = new AudioReceiver(myListeningPort, isPrivate);
                    receiverThread = new Thread(audioReceiver);
                    receiverThread.start();
                } else {
                    String[] temp = pair.split(":");
                    ipPortMap.put(temp[0], Integer.parseInt(temp[1].trim()));
                }
            }

            // For outgoing audio (sending to the other client)
            // The other client will send to this port
            audioSender = new AudioSender(myIp, ipPortMap, isPrivate);
            senderThread = new Thread(audioSender);
            senderThread.start();
            
            inCall = true;
        }
    }
    /**
     * Function to end the current call the user is on, ends their audioSender and audioReceiver threads safely
     */
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
    }
    /**
     * Function to send a message to the server
     */
    private void communicate(String msg) {
        try {
            output.write(msg);
            output.newLine();
            output.flush();
        } catch (IOException e) {
            System.err.println("Error sending message: " + e.getMessage());
        }
    }
    /* ################################################################
     * ######################## GUI METHODS ###########################
     * ################################################################*/

    /**
     * Logs in the user and sends the username and IP address to the server, getting a response back
     * to confirm the username is valid. If the username is invalid, the user is prompted to enter
     * a new username until a valid one is entered.
     */
    private void logInUser(){
        userInfo = promptUserInfo("");
        if (userInfo == null) {
            close();
            System.exit(0);
        }
        username = userInfo[0];
        myIp = userInfo[1];

        //Send name to server and receive response
        try {
            output.write(username);
            output.newLine();
            output.flush();
            String response = input.readLine();

            if (response.equals("Username accepted.")) {
                // Send IP address to server
                output.write(myIp);
                output.newLine();
                output.flush();
                return;
            } else {
                // First time unsuccessful, Stay in while loop till username is valid
                while (true) {
                    userInfo = promptUserInfo(response);
                    username = userInfo[0];
                    myIp = userInfo[1];

                    output.write(username);
                    output.newLine();
                    output.flush();
                    response = input.readLine();
                    if (response.equals("Username accepted.")) {
                        output.write(myIp);
                        output.newLine();
                        output.flush();
                        return;
                    } else {
                        response = input.readLine();
                    }
                }  
            }
        } catch (IOException e) {
            System.out.println("Unsuccesful sending username to Server");
        }
    }
    /**
     * Prompts the user for a username and IP address, and returns a String array of [username, IPaddress]
     * If the user cancels the dialog, the program will exit.
     * @param errMessage the error message to take note of if the username is invalid
     * @return a String array of [username, IP address]
     */
    private String[] promptUserInfo(String errMessage) {
          // Create custom dialog box
         Dialog<String[]> dialog = new Dialog<>();
         dialog.setTitle("Log In");
         dialog.setResizable(false);
 
         // Set appropriate header text
         if (errMessage.equals("Username cannot be empty.")) {
            dialog.setHeaderText("Username my not be empty, try again.");
         } else if (errMessage.equals("Username already taken.")) {
            dialog.setHeaderText("Username already taken, try again.");
         } else {
            dialog.setHeaderText("Welcome to the Chillax VoIP server.");
         }
 
         // Create labels for the dialog box
         Label usernameLabel = new Label("Username: ");
         TextField userNameField = new TextField();
         userNameField.setText(username);
         userNameField.setStyle("-fx-control-inner-background: rgb(69, 69, 69); -fx-font-size: 14; -fx-font-color: white");
         GridPane.setHgrow(userNameField, Priority.ALWAYS);
 
         Label iPLabel = new Label("IP Address: ");
         TextField iPTextField = new TextField();
         iPTextField.setText(myIp);
         iPTextField.setStyle("-fx-control-inner-background: rgb(69, 69, 69); -fx-font-size: 14; -fx-font-color: white");
         GridPane.setHgrow(iPTextField, Priority.ALWAYS);
 
         // Create layout
         GridPane grid = new GridPane();
         grid.setHgap(10);
         grid.setVgap(10);
         grid.add(usernameLabel, 0, 0);
         grid.add(userNameField, 1, 0);
         grid.add(iPLabel, 0, 1);
         grid.add(iPTextField, 1, 1);
         dialog.getDialogPane().setContent(grid);
         dialog.getDialogPane().setStyle("-fx-background: rgb(45, 45, 45); -fx-font-size: 14; -fx-font-color: white");
 
         // Add Log In and Cancel buttons
         dialog.getDialogPane().getButtonTypes().add(ButtonType.OK);
         dialog.getDialogPane().lookupButton(ButtonType.OK).setStyle("-fx-background-color: rgb(200, 20, 250); -fx-font-size: 14; -fx-text-fill: white");
         dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
         dialog.getDialogPane().lookupButton(ButtonType.CANCEL).setStyle("-fx-background-color: rgb(200, 20, 250); -fx-font-size: 14; -fx-text-fill: white");
 
 
         // Set action for OK button
         dialog.setResultConverter(dialogButton -> {
             if (dialogButton == ButtonType.OK) {
                     return new String[] { userNameField.getText(), iPTextField.getText() };
             } else return null;
         });
 
         // Show dialog and wait for result
         Optional<String[]> result = dialog.showAndWait();
         return result.orElseGet(() -> {
             Platform.exit();
             return null;
         });
     }

    /**
     * Builds the global chat area along with the input field for the user to send messages,
     * send voice notes and have global calls. The global chat area is a scrollable text
     * area that displays all the messages that the user has received. The input
     * field is a text field where the user can type in messages, and there are
     * two buttons that the user can use to start voice notes and calls.
     */
    private VBox buildGroupsBox() {
        Label globalChatLabel = new Label("Global/Group Chat Area");
        globalChatLabel.setStyle("-fx-text-fill: white; -fx-font-size: 15; -fx-font-weight: bold");

        globalChatOutput = new TextArea();
        globalChatOutput.setEditable(false);
        globalChatOutput.setWrapText(true);
        globalChatOutput.setStyle("-fx-control-inner-background: rgb(69, 69, 69); -fx-font-size: 14");

        TextField globalChatInput = new TextField();
        globalChatInput.setPromptText("Type message here...");
        globalChatInput.setOnAction(e -> sendMessage(globalChatOutput, globalChatInput));
        globalChatInput.setStyle("-fx-background-color:rgb(69, 69, 69); -fx-text-fill: white; -fx-font-size: 14");

        Button voiceNoteButton = new Button("Start Voicenote");
        voiceNoteButton.setOnAction(e -> startVoiceNote(voiceNoteButton, globalChatOutput, "Global"));
        voiceNoteButton.setStyle("-fx-background-color:rgb(200, 20, 250); -fx-text-fill: white; -fx-font-size: 14; -fx-font-weight: bold");

        Button callButton = new Button("Start Call");
        callButton.setOnAction(e -> startCall(callButton, globalChatOutput, "Global"));
        callButton.setStyle("-fx-background-color: rgb(200, 20, 250); -fx-text-fill: white; -fx-font-size: 14; -fx-font-weight: bold");

        HBox inputBox = new HBox(globalChatInput, voiceNoteButton, callButton);
        HBox.setHgrow(globalChatInput, Priority.ALWAYS);
        inputBox.setSpacing(5);
        inputBox.setPadding(new Insets(5, 5, 5, 5));
        VBox globalchatBox = new VBox(globalChatLabel, globalChatOutput, inputBox);
        VBox.setVgrow(globalChatOutput, Priority.ALWAYS);
        VBox groupsBox = new VBox(globalchatBox);
        VBox.setVgrow(globalchatBox, Priority.ALWAYS);
        groupsBox.setPrefWidth(500);
        groupsBox.setPrefHeight(700);
        groupsBox.setStyle("-fx-background-color: rgb(40, 45, 50)");
        return groupsBox;
    }
    
    /**
     * Sends a message to the server and updates the GUI to reflect
     * the message being sent.
     * @param outputArea The TextArea to append the message to
     * @param inputArea The TextField to clear after sending the message
     */
    private void sendMessage(TextArea outputArea, TextField inputArea) {
        String message = inputArea.getText();
        if (!message.isEmpty()) {
            new Thread(() -> {  
                try {
                    output.write(message);
                    output.newLine();
                    output.flush();

                    // Visuals on Client sending message GUI
                    Platform.runLater(() -> {
                        outputArea.appendText(username + ": " + message + "\n");
                        inputArea.clear();
                    });
                    
                } catch (IOException e) {
                    System.out.println("Error sending message: "  + e.getMessage());
                    close();
                }
            }).start();
            
        }
    }

    /**
     * Starts a voice note recording and sets up the stop button to stop it
     * @param recordButton The button to change to a stop button
     * @param outputArea The area to output the status of the recording
     * @param receiverName The name of the person/people to send the voice note to
     */
    private void startVoiceNote(Button recordButton, TextArea outputArea, String receiverName) {
        // Build temp direcotry in constructor of voicenote and start recording
        voiceNote = new VoiceNotes(username);

        if (voiceNote.startRecording()) {
            outputArea.appendText("Recording started\n");
            isRecording.set(true);
        } else {
            outputArea.appendText("Unable to start recording\n");
        }

        recordButton.setText("Stop Voicenote");
        recordButton.setOnAction(e -> stopVoiceNote(recordButton, outputArea, receiverName));
    }
    
    /**
     * Stops the current voice note recording and requests for the IPs of the receivers from the server.
     * @param voiceNoteButton The button that was used to start/stop the recording
     * @param outputArea The area to output the status of the recording
     * @param receiverName The name of the group or user to send the voice note to
     */
    private void stopVoiceNote(Button voiceNoteButton, TextArea outputArea, String receiverName) {
        // Stop the recording and get the recording file
        curSelectedFile = voiceNote.stopRecording();
        outputArea.appendText("Recording stopped successfully\n");
        isRecording.set(false);
        
        // Get IPs of all receivers from the server
        if (receiverName.equals("Global")) {    // Global voice note /getIps @Global
            communicate("/getIps " + "@" + receiverName);
        } else if (myGroups.containsKey(receiverName)) { // Group voice note /getIps @[groupName]
            communicate("/getIps " + "@" + receiverName);
        } else {                                         // Private voice note /getIps [receiverName]
            communicate("/getIps " + receiverName);
        }
        voiceNoteButton.setText("Start Voicenote");
        voiceNoteButton.setOnAction(e -> startVoiceNote(voiceNoteButton, outputArea, receiverName));
    }

    /**
     * Sends a voice note to one or multiple recipients based on the parsed message.
     * Extracts IP addresses and the receiver's name from the given message,
     * then sends the current selected voice note file to each IP address.
     * Communicates the action to the server for logging or further processing.
     *
     * @param msg The message containing recipient IP addresses and the receiver's name.
     */

    private void sendVoiceNote (String msg) {
        String receivedIPs = msg.substring(msg.indexOf(":") + 1, msg.indexOf("@"));
        String receiverName = msg.substring(msg.indexOf("@") + 1);
        if (receivedIPs.indexOf(',') == -1) {
            VoiceNoteTransfer.sendVoiceNote(receivedIPs, VN_PORT, curSelectedFile, username);
            communicate("@" + receiverName + " sent a voicenote");
        } else {
            String[] ipArray = receivedIPs.split(",");
            for (String ip: ipArray) {
                VoiceNoteTransfer.sendVoiceNote(ip, VN_PORT, curSelectedFile, username);
            }
            
            if (receiverName.equals("Global")) {
                communicate("sent a voice note");
            } else {
                communicate("/groupmsg" + "@" + receiverName + "-" + "sent a voice note");
            }
        }
    }

    /**
     * Starts a call to the given receiver, either global or private.
     * Checks if the given receiver is global or private and sends the appropriate command to the server.
     * If the receiver is a private call, it also checks if there are any other open call windows and flips them to start call with the new event handler.
     * Finally it sets the button to stop call with the new event handler.
     * @param callButton the button to start or stop the call
     * @param outputArea the text area to show the call status (mostly used for opening whisper windows on the other client when they don't exit yet)
     * @param receiverName the name of the receiver
     */
    private void startCall(Button callButton, TextArea outputArea, String receiverName) {
        // Check if its global, group or private call
        callee[0] = callee[1];
        callee[1] = receiverName;
        if (receiverName.equals("Global")) {    // Global call
            communicate("Call global");
        } else if (myGroups.containsKey(receiverName)) { // Group call
            communicate("Call " + receiverName);
        } else {                                         // Private call
            communicate("Call " + receiverName);
            // if the openwispwindows is not empty, check if there is a button with text "Stop Call" and flip it to start call with the new event handler
            if (!openWispWindows.isEmpty()) {
                for (String client: openWispWindows.keySet()) {
                    if (!client.equals(receiverName)) {
                        VBox wispBox = openWispWindows.get(client);
                        HBox wispInputBox = (HBox) wispBox.getChildren().get(1);
                        Button wispCallButton = (Button) wispInputBox.getChildren().get(2);
                        if (wispCallButton.getText().equals("Stop Call")) {
                            wispCallButton.setText("Start Call");
                            wispCallButton.setOnAction(e -> startCall(wispCallButton, outputArea, receiverName));
                        }
                    }
                }
            }
        }

        callButton.setText("Stop Call");
        callButton.setOnAction(e -> stopCall(callButton, outputArea, receiverName));
    }

    /**
     * Stops the current call and sends a message to the server to end the call for the other users depending on the call type.
     * Sets the button to start call with the new event handler.
     * @param callButton the button to start or stop the call
     * @param outputArea the text area to show the call status
     * @param receiverName the name of the receiver or "Global" for a global call
     */
    private void stopCall(Button callButton, TextArea outputArea, String receiverName) {
        if (receiverName.equals("Global")) {
            communicate("CALL ENDED: global");
        } else if (myGroups.containsKey(receiverName)) {
            communicate("CALL ENDED: " + receiverName);
        } else {
             communicate("CALL ENDED: " + receiverName);
         }
        endCurrentCall();

        callButton.setText("Start Call");
        callButton.setOnAction(e -> startCall(callButton, outputArea, receiverName));
    }

    /**
     * Builds the box that contains all the active whisper windows.
     * @return A VBox containing a label and an empty VBox for the active whisper windows
     */
    private VBox buildWispBox() {
        Label wispLabel = new Label("Whisper Windows");
        wispLabel.setStyle("-fx-text-fill: white; -fx-font-size: 15; -fx-font-weight: bold");
        VBox activeWispsBox = new VBox();
        VBox wispBox = new VBox(wispLabel, activeWispsBox);
        wispBox.prefHeight(700);
        wispBox.prefWidth(500);
        wispBox.setStyle("-fx-background-color: rgb(40, 45, 50)");
        
        return wispBox;
    }
    
    /**
     * Builds the box that contains the active client list.
     * @return A VBox containing a label and the active client list
     */
    private VBox buildClientBox() {
        Label listLabel = new Label("Active Client list");
        listLabel.setStyle("-fx-text-fill: white; -fx-font-size: 15; -fx-font-weight: bold");

        activeClientList.setPrefWidth(175);
        activeClientList.setPrefHeight(600);
        activeClientList.setEditable(false);

        activeClientList.setCellFactory(param -> new ListCell<String>() {
            Label clientLabel = new Label();
            Button wispButton = new Button("Whisper");
            VBox clientBox = new VBox(3, clientLabel, wispButton);

            {
                clientLabel.setStyle("-fx-text-fill: white");
                wispButton.setVisible(false);
                wispButton.setStyle("-fx-background-color: rgb(200, 20, 250); -fx-text-fill: white;-fx-font-weight: bold");
                clientBox.setPadding(new Insets(2));
                clientBox.setAlignment(Pos.CENTER);
                clientBox.setStyle("-fx-background-color: rgb(69, 69, 69)");
            }

            @Override
            protected void updateItem(String chosenClient, boolean empty) {
                super.updateItem(chosenClient, empty);

                if (empty || chosenClient == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("-fx-background-color: rgb(69, 69, 69);");
                } else {
                    clientLabel.setText(chosenClient);
                    setText(null);
                    setGraphic(clientBox);

                    if (chosenClient.equals(username)) {
                        wispButton.setVisible(false);
                        wispButton.setDisable(true);
                        clientLabel.setStyle("-fx-text-fill: rgb(230, 135, 255); -fx-font-size: 15; -fx-font-weight: bold");
                    } else {
                        clientBox.setOnMouseEntered(e -> wispButton.setVisible(true));
                        clientBox.setOnMouseExited(e -> wispButton.setVisible(false));
                        wispButton.setOnAction(e -> buildWispWindow(chosenClient, null));
                        clientLabel.setStyle("-fx-text-fill: white; -fx-font-size: 15");
                    }
                }
            }
        });

        VBox activeClientBox = new VBox(listLabel, activeClientList);
        activeClientBox.setStyle("-fx-background-color: rgb(40, 45, 50)");
        activeClientBox.setPadding(new Insets(0, 10, 5, 5));

        return activeClientBox;
    }
    
    /**
     * Builds a VBox containing additional client options, including buttons for 
     * creating a group, choosing a file, and closing the client.
     *
     * @param clientListBox The VBox containing the client list.
     * @param groupsBox The VBox containing the global chat and other open group chats.
     * @param mainStage The main stage of the application for file choosing context.
     * @return A VBox containing the styled buttons for additional client actions.
     */
    private VBox buildExtras(VBox clientListBox, VBox groupsBox, Stage mainStage) {
        Button createGroupButton = new Button("Create Group");
        createGroupButton.setPrefWidth(140);
        createGroupButton.setOnAction(e -> promptGroupSelection(clientListBox, createGroupButton, groupsBox));
        createGroupButton.setStyle("-fx-background-color: rgb(200, 20, 250); -fx-text-fill: white; -fx-font-size: 14; -fx-font-weight: bold");

        Button chooseFileButton = new Button("Choose File");
        chooseFileButton.setPrefWidth(140);
        chooseFileButton.setOnAction(e -> chooseFile(mainStage));
        chooseFileButton.setStyle("-fx-background-color: rgb(200, 20, 250); -fx-text-fill: white; -fx-font-size: 14; -fx-font-weight: bold");

        Button exitButton = new Button("Close Client");
        exitButton.setPrefWidth(140);
        exitButton.setOnAction(e -> closeClient());
        exitButton.setStyle("-fx-background-color: rgb(245, 45, 45); -fx-text-fill: white; -fx-font-size: 14; -fx-font-weight: bold");

        VBox extrasBox = new VBox(createGroupButton, chooseFileButton, exitButton);
        extrasBox.prefWidth(150);
        extrasBox.setSpacing(5);
        extrasBox.setPadding(new Insets(5, 0, 5, 0));
        extrasBox.setAlignment(Pos.CENTER);
        extrasBox.setStyle("-fx-background-color: rgb(40, 45, 50);");

        return extrasBox;

    }

    /**
     * Closes the client application by performing cleanup operations.
     * Deletes the directory used for storing received voice notes and
     * sends an exit message to the server to terminate the connection.
     */
    private void closeClient() {
        deleteDirectory(receivedDirectory);
        try {
            output.write("/exit");
            output.newLine();
            output.flush();
        } catch (IOException e) {
            System.out.println("Error sending exit message: "  + e.getMessage());
        }
    }
    
    /**
     * Builds the main BorderPane of the application, which contains the global/group chat area, 
     * the active whisper windows, and the client list the extra buttons bottom right.
     * 
     * @param groupsBox The VBox containing the global chat and other open group chats.
     * @param wispBox The VBox containing the active whisper windows.
     * @param rightBox The VBox containing the client list and buttons on the right.
     * @return A BorderPane containing the styled main components of the application.
     */
    private BorderPane buildBackground( VBox groupsBox, VBox wispBox, VBox rightBox) {
        BorderPane backgroundPane = new BorderPane();
        backgroundPane.setLeft(groupsBox);
        backgroundPane.setCenter(wispBox);
        backgroundPane.setRight(rightBox);
        return backgroundPane;
    }
    
    /**
     * Builds a new whisper window for the given receiver name.
     * @param receiverName the name of the receiver of the whisper window
     * @param msg the message to be displayed in the window, or null if no message should be displayed initially
     * @return A VBox containing the whisper window components
     */
    private VBox buildWispWindow(String receiverName, String msg) {
        if (openWispWindows.containsKey(receiverName)) {
            return openWispWindows.get(receiverName);
        }
        
        TextArea wispOutput = new TextArea();
        wispOutput.setEditable(false);
        wispOutput.setWrapText(true);
        wispOutput.setStyle("-fx-control-inner-background: rgb(69, 69, 69); -fx-font-size: 14; -fx-text-fill: white");

        TextField wispInput = new TextField();
        wispInput.setPromptText("Whisper " + receiverName + "...");
        wispInput.setOnAction(e -> sendWisp(wispInput, wispOutput, receiverName));
        wispInput.setStyle("-fx-background-color: rgb(69, 69, 69); -fx-font-size: 13; -fx-text-fill: white");

        Button voiceNoteButton = new Button("Start Voicenote");
        voiceNoteButton.setOnAction(e -> startVoiceNote(voiceNoteButton, wispOutput, receiverName));
        voiceNoteButton.setStyle("-fx-background-color: rgb(200, 20, 250); -fx-text-fill: white; -fx-font-size: 13; -fx-font-weight: bold");

        Button callButton = new Button("Start Call");
        callButton.setOnAction(e -> startCall(callButton, wispOutput, receiverName));
        callButton.setStyle("-fx-background-color: rgb(200, 20, 250); -fx-text-fill: white; -fx-font-size: 13; -fx-font-weight: bold");

        Button closeButton = new Button("Close chat");
        closeButton.setStyle("-fx-background-color: rgb(200, 20, 250); -fx-text-fill: white; -fx-font-size: 13; -fx-font-weight: bold");

        HBox wispInputBox = new HBox(wispInput, voiceNoteButton, callButton, closeButton);
        wispInputBox.setSpacing(5);
        HBox.setHgrow(wispInput, Priority.ALWAYS);
        wispInputBox.setStyle("-fx-background-color: rgb(40, 45, 50)");

        VBox wispWindow = new VBox(wispOutput, wispInputBox);
        wispWindow.setSpacing(5);
        wispWindow.setPadding(new Insets(5, 10, 5, 10));
        wispWindow.setStyle("-fx-background-color: rgb(40, 45, 50);");

        wispBox.getChildren().add(wispWindow);
        openWispWindows.put(receiverName, wispWindow);
        closeButton.setOnAction(e -> {
            wispBox.getChildren().remove(wispWindow);
            openWispWindows.remove(receiverName);
        });


        if (msg != null) {
            wispOutput.appendText(receiverName + ": " + msg + "\n");
        }
        return wispWindow;
    }

    /**
     * Prompts the user to select clients to create a new group chat.
     * Opens a window with a list of all active clients except the current user, and a text field to enter the group name.
     * The user can select clients from the list by clicking on them, and the selected clients are
     * highlighted. The user can then enter a name for the group and click the "Build Group" button
     * to create the group chat. The group chat is then added to the group chat area.
     * @param clientListBox The VBox containing the active client list.
     * @param createGroupButton The Button to create a new group chat.
     * @param groupsBox The VBox containing the global chat and other open group chats.
     */
    private void promptGroupSelection(VBox clientListBox, Button createGroupButton, VBox groupsBox) {
        HashMap<String, String> groupMembers = new HashMap<>();
        AtomicReference<String> groupName = new AtomicReference<>("");
        
        Label groupLabel = new Label("Member Selection:");
        groupLabel.setStyle("-fx-text-fill: white; -fx-font-size: 15; -fx-font-weight: bold");

        ListView<String> groupList = new ListView<String>();
        groupList.setPrefWidth(150);
        groupList.setCellFactory(param -> new ListCell<String>(){

            {
                setOnMouseClicked(e -> {
                    String chosenClient = getItem();
                    if (chosenClient == null) {
                        return;
                    } else if (groupMembers.containsKey(chosenClient)) {
                        groupMembers.remove(chosenClient);
                    } else {
                        groupMembers.put(chosenClient, chosenClient);
                    }
                    groupList.refresh();
                });
            }


            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("-fx-background-color: rgb(69, 69, 69);");
                } else {
                    setText(item);
                    setTextFill(Color.WHITE);
                    setFont(Font.font(14));
                    
                    if (groupMembers.containsKey(item)) {
                        setStyle("-fx-background-color: rgb(200, 20, 250);");
                    } else {
                        setStyle("-fx-background-color: rgb(69, 69, 69);");
                    }
                }
            }
        });

        for (String client : activeClientList.getItems()) {
            if (client.equals(username)) {
                groupMembers.put(client, client);
                continue;
            } else {
                groupList.getItems().add(client);
            }
        }

        Label groupNameLabel = new Label("Group Name:");
        groupNameLabel.setStyle("-fx-text-fill: white; -fx-font-size: 15; -fx-font-weight: bold");

        TextField groupNameInput = new TextField();
        groupNameInput.setPromptText("Group name...");
        groupNameInput.setOnAction(e -> {
            String tempName = groupNameInput.getText();
            if (!tempName.isEmpty()) {
                groupName.set(tempName);
                groupNameInput.setPromptText("Name successfully set!");
            } else {
                groupNameInput.setPromptText("Enter valid group name...");
            }
        });

        groupNameInput.setStyle("-fx-background-color: rgb(69, 69, 69); -fx-font-size: 13; -fx-text-fill: white");

        Button cancelButton = new Button("Cancel");
        cancelButton.setPrefWidth(140);
        cancelButton.setStyle("-fx-background-color: rgb(245, 45, 45); -fx-text-fill: white; -fx-font-size: 13; -fx-font-weight: bold");

        VBox groupNameBox = new VBox(groupNameLabel, groupNameInput, cancelButton);
        groupNameBox.setPadding(new Insets(5, 5, 5, 5));
        groupNameBox.setSpacing(5);
        groupNameBox.setAlignment(Pos.CENTER);
        groupNameBox.setStyle("-fx-background-color: rgb(40, 45, 50)");

        VBox groupBox = new VBox(groupLabel, groupList, groupNameBox);
        groupBox.setPadding(new Insets(5));
        groupBox.setSpacing(5);
        groupBox.setStyle("-fx-background-color: rgb(40, 45, 50)");

        clientListBox.getChildren().add(groupBox);

        createGroupButton.setText("Build Group");
        createGroupButton.setOnAction(e -> {
            if (groupName.get().isEmpty()) {
                groupNameInput.setPromptText("Enter valid group name...");
                return;
            } else {
                createGroup(groupName.get(), groupMembers, groupsBox);
                clientListBox.getChildren().remove(groupBox);
                createGroupButton.setText("Create Group");
                createGroupButton.setOnAction(e2 -> promptGroupSelection(clientListBox, createGroupButton, groupsBox));
            }
        });

        cancelButton.setOnAction(e -> {
            clientListBox.getChildren().remove(groupBox);
            createGroupButton.setText("Create Group");
            createGroupButton.setOnAction(e2 -> promptGroupSelection(clientListBox, createGroupButton, groupsBox));
        });
    }

    /**
     * Sends a whisper message to a spesified receiver.
     * Runs in a separate thread to prevent UI blocking and responsiveness issues.
     * Updates the GUI to display the sent message.
     * 
     * @param wispInput The TextField containing the message to be sent
     * @param wispOutput The TextArea where the sent message is appended
     * @param receiver The name of the receiver of the whisper message
     */
    private void sendWisp(TextField wispInput, TextArea wispOutput, String receiver) {
        String message = wispInput.getText();

         // Same as send messages, assign its own thread to avoid stoppages and bad responsiveness
         new Thread(() -> {
            try {
                output.write("@" + receiver + " " + message);
                output.newLine();
                output.flush();

                Platform.runLater(() -> {
                    wispOutput.appendText(username + ": " + message + "\n");
                    wispInput.clear();
                });

            } catch (IOException e) {
                System.out.println("Error sending whisper: "  + e.getMessage());
                close();
            }
        }).start();

    }

    /**
     * Builds a new group chat window for the given group name and member list.
     * The window contains a Label with the group name and member list, a TextArea for displaying messages,
     * a TextField for inputting messages, a Button for starting a voice note, and a Button for closing the window.
     * @param groupsBox The VBox containing the global chat and other open group chats.
     * @param groupName The name of the group
     * @param groupMembers A HashMap containing the members of the group, where the key and value are the same
     * @return A VBox containing the group chat window components
     */
    private VBox buildGroupChat(VBox groupsBox, String groupName, HashMap<String, String> groupMembers) {
        String allMembers = String.join(", ", groupMembers.keySet());

        Label groupChatLabel = new Label(groupName + " (" + allMembers + ")");
        groupChatLabel.setStyle("-fx-text-fill: white; -fx-font-size: 14; -fx-font-weight: bold");

        TextArea groupChatOutput = new TextArea();
        groupChatOutput.setEditable(false);
        groupChatOutput.setWrapText(true);
        groupChatOutput.setStyle("-fx-control-inner-background: rgb(69, 69, 69); -fx-font-size: 13; -fx-text-fill: white");

        TextField groupChatInput = new TextField();
        groupChatInput.setPromptText("Message group...");
        groupChatInput.setOnAction(e -> sendGroupMessage(groupChatOutput, groupChatInput, groupName));
        groupChatInput.setStyle("-fx-background-color: rgb(69, 69, 69); -fx-font-size: 12; -fx-text-fill: white");

        Button voiceNoteButton = new Button("Start Voicenote");
        voiceNoteButton.setOnAction(e -> startVoiceNote(voiceNoteButton, groupChatOutput, groupName));
        voiceNoteButton.setStyle("-fx-background-color: rgb(200, 20, 250); -fx-text-fill: white; -fx-font-size: 12; -fx-font-weight: bold");

        Button closeButton = new Button("Close");
        closeButton.setStyle("-fx-background-color: rgb(200, 20, 250); -fx-text-fill: white; -fx-font-size: 12; -fx-font-weight: bold");

        HBox groupChatInputBox = new HBox(groupChatInput, voiceNoteButton, closeButton);
        groupChatInputBox.setSpacing(5);
        HBox.setHgrow(groupChatInput, Priority.ALWAYS);
        groupChatInputBox.setStyle("-fx-background-color: rgb(40, 45, 50)");

        VBox groupChatBox = new VBox(groupChatLabel, groupChatOutput, groupChatInputBox);
        groupChatBox.setPadding(new Insets(5));
        groupChatBox.setSpacing(5);
        groupChatBox.setStyle("-fx-background-color: rgb(40, 45, 50)");

        groupsBox.getChildren().add(groupChatBox);
        openGroups.put(groupName, groupChatBox);

        closeButton.setOnAction(e -> {
            sendGroupMessage(groupChatOutput, null, groupName);
            groupsBox.getChildren().remove(groupChatBox);
            myGroups.remove(groupName);
            openGroups.remove(groupName);
        });

        return groupChatBox;
    }
    
    /**
     * Populates the active client list in the GUI with the list of users received from the server.
     * The message received contains the usernames of currently online clients.
     * Clears the existing client list and updates it with the new list of users.
     * 
     * @param msg The message received from the server containing the list of online users.
     */
    private void populateClientList(String msg) {
        users = msg.substring(7).split(",");
        // Loop thorugh the ConcurrentHashmap of currently online users to populate the client list on the GUI
        Platform.runLater(() -> {
            activeClientMap.clear();
            for (int i = 0; i < users.length; i++) {
                activeClientMap.put(users[i], i);
            }
            activeClientList.getItems().clear();
            activeClientList.getItems().addAll(activeClientMap.keySet());
        });
    }

    /**
     * Removes the client from the active client list in the GUI when a "LEAVING:" message is received from the server.
     * The message received contains the username of the client that has left the chat.
     * Removes the client from the GUI's HashMap of active clients and updates the client list on the GUI to reflect that.
     * If the client had an open whisper window, the window is closed and removed from the GUI's HashMap of open whisper windows.
     * @param msg The message received from the server containing the username of the client that has left the chat.
     */
    private void removeClientFromList(String msg) {
        String leavingUser = msg.substring(msg.indexOf(" ") + 1);
        activeClientMap.remove(leavingUser);

        Platform.runLater(() -> {
            activeClientList.getItems().remove(leavingUser);

            if (!openWispWindows.isEmpty()) {
                for (String client: openWispWindows.keySet()) {
                    if (client.equals(leavingUser)) {
                        VBox wispWindow = openWispWindows.get(client);
                        wispBox.getChildren().remove(wispWindow);
                        openWispWindows.remove(client);
                    }
                }
            }
        });
    }
    
    /**
     * Handles incoming whisper messages and call notifications.
     * If the message is a whisper, it extracts the sender and message content,
     * and appends the message to the existing whisper window or creates a new
     * one if it doesn't exist.
     * If the message is a call notification, it sets the call button to "Stop Call"
     * and assigns the appropriate event handler.
     * 
     * @param msg The incoming message which could be a whisper or a call notification.
     */
    private void receiveIncomingWisp(String msg) {
        String sender, message;
        if (msg.startsWith("Call from")) {
            sender = msg.substring(msg.indexOf(":") + 1);
            message = null;
        } else {
            sender = msg.substring(13, msg.indexOf(":"));
            message = msg.substring(msg.indexOf(":") + 1);
        }

        //Gui implementation of wisp
        Platform.runLater(() -> {
            // Create wisp window if it does not exist
            if (!openWispWindows.containsKey(sender)) {
                VBox wispWindow = buildWispWindow(sender, message);
                openWispWindows.put(sender, wispWindow);
            // Append text to the open whisperWinodow if it does exist
            } else {
                VBox wispWindow = openWispWindows.get(sender);
                TextArea wispMessageOut = (TextArea) wispWindow.getChildren().get(0);
                if (!(msg.startsWith("Call from"))) {
                    wispMessageOut.appendText(sender + ":" + message + "\n");
                }
            }

            if (msg.startsWith("Call from")) {
                VBox wispWindow = openWispWindows.get(sender);
                TextArea wispMessageOut = (TextArea) wispWindow.getChildren().get(0);
                HBox wispInputBox = (HBox) wispWindow.getChildren().get(1);
                Button callButton = (Button) wispInputBox.getChildren().get(2);
                callButton.setText("Stop Call");
                callButton.setOnAction(e -> stopCall(callButton, wispMessageOut, sender));
            }
        });
    }
    
    /**
     * Opens a file chooser dialog for the user to select a file to send to other clients.
     * If the file is a voice note (.wav), the voice note interaction area is built under the global chat window.
     * @param mainstage The main stage of the application for file choosing context.
     */
    private void chooseFile(Stage mainstage) {
        File curDir = new File(System.getProperty("user.dir"));

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Choose a file to send");
        fileChooser.setInitialDirectory(curDir);

        fileChooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("All Files", "*.*"));
        curSelectedFile = fileChooser.showOpenDialog(mainstage);
        if (curSelectedFile != null && curSelectedFile.exists()) {
            String fileName = curSelectedFile.getName();
            // Check if the file is a voice note
            if (fileName.toLowerCase().endsWith(".wav")) {
                // Build the voice note interaction area under the global chat window
                Platform.runLater(() -> buildVoiceNoteArea());
            }
        }
    }
    
    /**
     * Creates a new group chat by sending a create group command to the server.
     * The command includes the group name and a list of group members.
     * A new thread is started to handle the server communication.
     * Once the group is created, it updates the UI to display the new group chat window,
     * and adds the group to the local list of user groups.
     * 
     * @param groupName The name of the group to be created.
     * @param groupMembers A HashMap containing the members of the group.
     * @param groupsBox The VBox containing the global chat and other open group chats.
     */
    private void createGroup(String groupName, HashMap<String, String> groupMembers, VBox groupsBox) {
        new Thread(() -> {
            String identifier = "/creategroup";
            try {
                output.write(identifier + "@" + groupName + "-" + String.join(",", groupMembers.keySet()));
                output.newLine();
                output.flush();

                Platform.runLater(() -> {
                    buildGroupChat(groupsBox, groupName, groupMembers);
                    myGroups.put(groupName, groupMembers);
                });
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }
    
    /**
     * Handles a received group command from the server.
     * Parses the command to extract the group name and its members.
     * Then builds a new group chat window and adds the group to the local list of user groups.
     * @param msg The received group command from the server.
     */
    private void receiveGroup(String msg) {
        String groupName = msg.substring(msg.indexOf("@") + 1, msg.indexOf("-"));
        String membersString = msg.substring(msg.indexOf("-") + 1);
        String[] membersArray = membersString.split(",");
        HashMap<String, String> groupMembers = new HashMap<>();

        for (String member: membersArray) {
            groupMembers.put(member, member);
        }

        Platform.runLater(() -> {
            VBox groupChatBox = buildGroupChat(groupsBox, groupName, groupMembers);
            myGroups.put(groupName, groupMembers);
            openGroups.put(groupName, groupChatBox);
        });
    }
    
    /**
     * Handles a received group message from the server.
     * Parses the message to extract the sender, group name, and message content.
     * If the group chat window is open, appends the message to the chat area.
     * @param msg The received group message from the server.
     */
    private void receiveGroupMessage(String msg) {
        String sender = msg.substring(msg.indexOf("/") + 1, msg.indexOf(":"));
        String groupName = msg.substring(msg.indexOf("@") + 1, msg.indexOf("-"));
        String message = msg.substring(msg.indexOf("-") + 1);


        if (openGroups.containsKey(groupName)) {
            VBox groupChatBox = openGroups.get(groupName);
            TextArea groupChatOutput = (TextArea) groupChatBox.getChildren().get(1);
            groupChatOutput.appendText(sender + ": " + message + "\n");
        }
    }
    
    /**
     * Sends a message to the server to be sent to a group with the given name.
     * The message is retrieved from the inputArea, and the outputArea is updated
     * to reflect the message being sent.
     * A new thread is started to handle the server communication.
     * @param outputArea The TextArea to append the message to
     * @param inputArea The TextField to clear after sending the message
     * @param groupName The name of the group to send the message to
     */
    private void sendGroupMessage(TextArea outputArea, TextField inputArea, String groupName) {
        Thread groupMessageThread = new Thread(() -> {
            String identifier = "/groupmsg";
            if (inputArea != null) {
                String msg = inputArea.getText();
                try {
                    output.write(identifier + "@" + groupName + "-" + msg);
                    output.newLine();
                    output.flush();

                    Platform.runLater(() -> {
                        outputArea.appendText(username + ": " + msg + "\n");
                        inputArea.clear();
                    });
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else {
                try {
                    output.write(identifier + "@" + groupName + "-" + "Left the group");
                    output.newLine();
                    output.flush();

                    Platform.runLater(() -> {
                        outputArea.appendText(username + ": " + "Left the group" + "\n");
                    });
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
        groupMessageThread.start();
    }
    
    /**
     * Builds the voice note area, which is a horizontal box containing the "Play", "Stop", and "Close" buttons for the voice note.
     * The "Play" button starts the voice note playback, 
     * the "Stop" button stops the voice note playback, 
     * and the "Close" button removes the voice note area from the screen and stops any playing voice note.
     * The voice note area is added to the groupsBox VBox.
     */
    private void buildVoiceNoteArea() {
        Label vnLabel = new Label("Voice note options:");
        vnLabel.setStyle("-fx-text-fill: white; -fx-font-size: 15; -fx-font-weight: bold");
        Button playButton = new Button("Play");
        playButton.setPrefWidth(78);
        playButton.setOnAction(e -> playVoiceNote(curSelectedFile));
        playButton.setStyle("-fx-background-color: rgb(200, 20, 250); -fx-text-fill: white; -fx-font-size: 14; -fx-font-weight: bold");

        Button stopButton = new Button("Stop");
        stopButton.setPrefWidth(80);
        stopButton.setOnAction(e -> {
            if (curSoundClip != null && curSoundClip.isOpen()) {
                curSoundClip.close();
            }
        });
        stopButton.setStyle("-fx-background-color: rgb(200, 20, 250); -fx-text-fill: white; -fx-font-size: 14; -fx-font-weight: bold");

        Button closeButton = new Button("Close");
        closeButton.setPrefWidth(80);
        closeButton.setStyle("-fx-background-color: rgb(245, 45, 45); -fx-text-fill: white; -fx-font-size: 14; -fx-font-weight: bold");
        HBox vnBox = new HBox(5, vnLabel, playButton, stopButton, closeButton);
        vnBox.setPadding(new Insets(5, 5, 10, 5));
        
        groupsBox.getChildren().add(vnBox);

        closeButton.setOnAction(e -> {
            if (curSoundClip != null) {
                curSoundClip.stop();
                curSoundClip.close();
            }
            groupsBox.getChildren().remove(vnBox);
        });
    }

    /**
     * Plays a voice note stored in a .wav file.
     * If a voice note is already playing, it is stopped and the new one is played.
     * @param wavFile the .wav file containing the voice note to be played
     */
    private void playVoiceNote(File wavFile) {
         try {
            // Reset clip if already playing something
            if (curSoundClip != null && curSoundClip.isOpen()) {
                curSoundClip.close();
            }

            AudioInputStream audioStream = AudioSystem.getAudioInputStream(wavFile);
            curSoundClip = AudioSystem.getClip();
            curSoundClip.open(audioStream);
            curSoundClip.start();

         } catch (Exception e) {
             e.printStackTrace();
         }
    }

    /**
     * Listens for incoming voice notes on a separate port. When a voice note is received, it is saved to a directory
     * named "received_voice_notes" in the current working directory.
     */
    private void listenForVoiceNotes() {
        // Create directory for received voice notes
        receivedDirectory = new File("received_voice_notes");
        if (!receivedDirectory.exists()) {
            receivedDirectory.mkdir();
        }

        try {
            // Create mini receiver port in the client to listen for voice notes
            ServerSocket vnsocket = new java.net.ServerSocket(VN_PORT);
            while (true) {
                try {
                    Socket clientSocket = vnsocket.accept();
                    // Try accepting incoming voice note 
                    new Thread(() -> {
                        try {
                            File receivedFile = VoiceNoteTransfer.receiveVoiceNote(clientSocket);
                            if (receivedFile != null) {
                                File destFile = new File(receivedDirectory, receivedFile.getName().substring(0, receivedFile.getName().length() - 20) + ".wav");
                                java.nio.file.Files.copy(receivedFile.toPath(), destFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            }
                        } catch (IOException e) {
                            System.err.println("Error receiving voice note");
                        }
                    }).start();
                } catch (IOException e) {
                    System.err.println("Error accepting connection");
                }
            }
        } catch (IOException e) {
            System.err.println("Error listening for voice notes");
        }
    }
    
    /**
     * The main entry point for the JavaFX application.
     * Launches the application with the provided command-line arguments.
     *
     * @param args Command-line arguments passed to the application
     */
    public static void main(String[] args) {
        launch(args);
    }


}
