// Backend imports
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ConcurrentHashMap; 

// GUI imports
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * This class provides the simplistic server GUI for the VoIP application.
 * Displays a log of user activities and well as displaying all currently connected clients
 */
public class ServerGui extends Application {

    private ServerSocket serverSocket;
    private ConcurrentHashMap<String, ClientHandler> clientList = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, String> IPlist = new ConcurrentHashMap<>();
    private TextArea userActivityOutput;
    private volatile boolean isRunning = false;
    private ListView<String> activeClientList = new ListView<String>();;
    
    /**
     * Main method for the Server GUI.
     * This method sets up the GUI components and starts the server thread.
     * @param mainStage The main stage of the application
     */
    @Override
    public void start(Stage mainStage){
        // Build GUI areas
        Platform.runLater(() ->  {
            VBox userActivityOutputBox = buildUserActivityOutput();
            VBox activeClientBox = buildClientList();
            HBox exitBox = buildExitArea();
            BorderPane backGroundPane = combineAreas(userActivityOutputBox, activeClientBox, exitBox);
    
            // Set up the scene and show the main stage
            Scene scene = new Scene(backGroundPane, 450, 670);
            mainStage.setTitle("Chillax Server");
            mainStage.setScene(scene);
            mainStage.show();
        });

        // Backend Launching of the server
        try {
            serverSocket = new ServerSocket(1235);
            Thread serverThread = new Thread(() -> backendStart());
            serverThread.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /* ################################################################
     * ################# BACKEND METHODS ##############################
     * ################################################################*/

    /**
     * This method starts the server and accepts incoming clients.
     * For each client, it accepts a username and IP address, and starts a new thread to handle the client.
     * It also adds the client to the client list and sends the client the list of online users.
     * When a client disconnects, this method is also responsible for removing the client from the list and
     * broadcasting the new list of online users to all other clients.
     */
     public void backendStart() {
        Platform.runLater(() -> userActivityOutput.appendText("Server started.\n"));
        isRunning = true;
        while (isRunning && !(serverSocket.isClosed())) {
            try {
                Socket socket = serverSocket.accept();
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
                
                //Deal with the username of the client
                String username, ipAddress;
                while (true) {
                    username = reader.readLine();
                    if (username == null) {
                        continue;
                    } else if (username.isEmpty()) {
                        writer.write("Username cannot be empty.");
                        writer.newLine();
                        writer.flush();
                        continue;
                    } else if (clientList.containsKey(username)) {
                        writer.write("Username already taken.");
                        writer.newLine();
                        writer.flush();
                        continue;
                    } else {
                        writer.write("Username accepted.");
                        writer.newLine();
                        writer.flush();
                        break;
                    }
                }
                ipAddress = reader.readLine();

                ClientHandler clientHandler = new ClientHandler(socket, clientList, username, IPlist, this);
                clientList.put(username, clientHandler);
                IPlist.put(username, ipAddress);

                //Synchronize the client list to avoid any race conditions and data corruption
                synchronized (clientList) {
                    clientHandler.groupChat(username + " has joined the chat.", true);
                    addClientGui(username);
                    broadcastOnlineUsers();
                }

                //create a thread for this client to provide concurrency
                Thread thread = new Thread(clientHandler);
                thread.start();
                            
            } catch (SocketException e) {
                break;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Broadcasts the list of currently online users to all connected clients.
     * This method is called whenever a new client joins the chat or when the
     * server is started.
     */
    public void broadcastOnlineUsers() {
        String online = "ONLINE:" + String.join(",", clientList.keySet());
        for (ClientHandler handler : clientList.values()) {
            handler.communicate(online);
        }
    }

    /* ################################################################
     * ######################## GUI METHODS ###########################
     * ################################################################*/

    /**
     * Builds the user activity log area of the server GUI.
     * The user activity log is a read-only text area that displays all
     * user activity on the server. The area is labeled as "User Activity Log".
     * @return A VBox containing the user activity log label and text area
     */
    private VBox buildUserActivityOutput(){
        Label userActivityLabel = new Label("User Activity Log");
        userActivityLabel.setStyle("-fx-text-fill: white; -fx-font-size: 15; -fx-font-weight: bold");

        userActivityOutput = new TextArea();
        userActivityOutput.setEditable(false);
        userActivityOutput.setPrefHeight(600);
        userActivityOutput.setPrefWidth(300);
        userActivityOutput.setStyle("-fx-control-inner-background: rgb(69, 69, 69); -fx-font-size: 14");

        VBox userActivityOutputBox = new VBox(userActivityLabel, userActivityOutput);
        return userActivityOutputBox;
    }

    /**
     * Builds the active client list area of the server GUI.
     * The active client list is a read-only list view that displays all
     * currently connected clients. The area is labeled as "Active Client List".
     * @return A VBox containing the active client list label and list view
     */
    private VBox buildClientList(){

        Label activeClientLabel = new Label("Active Client List");
        activeClientLabel.setStyle("-fx-text-fill: white; -fx-font-size: 15; -fx-font-weight: bold");

        activeClientList.setPrefWidth(150);
        activeClientList.setPrefHeight(600);
        activeClientList.setEditable(false);
        activeClientList.setStyle("-fx-control-inner-background: rgb(69, 69, 69); -fx-font-size: 14");

        activeClientList.setCellFactory(param -> new ListCell<String>() {
            @Override
            protected void updateItem(String chosenClient, boolean empty) {
                super.updateItem(chosenClient, empty);

                if (empty || chosenClient == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(chosenClient);
                }
                setStyle("-fx-background-color: rgb(69, 69, 69);");
            }
        });

        VBox activeClientBox = new VBox(activeClientLabel, activeClientList);
        return activeClientBox;
    }

    /**
     * Builds the exit area of the server GUI.
     * The exit area is a horizontal box containing a single button labeled "Close Server".
     * When the button is clicked, the server is shut down safely.
     * @return A HBox containing the exit button
     */
    private HBox buildExitArea(){
        Button exitButton = new Button("Close Server");
        exitButton.prefHeight(90);
        exitButton.setOnAction(e -> closeServer());
        exitButton.setStyle("-fx-background-color:rgb(245, 45, 45); -fx-text-fill: white; -fx-font-size: 14; -fx-font-weight: bold");

        HBox exitBox = new HBox(exitButton);
        exitBox.setStyle("-fx-background-color: rgb(40, 45, 50);");
        exitBox.prefHeight(100);
        exitBox.prefWidth(450);
        exitBox.setPadding(new Insets(0, 0, 10, 0));
        exitBox.setAlignment(Pos.TOP_CENTER);

        return exitBox;
    }

    /**
     * Shuts down the server and closes the application.
     * This method is called when the exit button is clicked in the server GUI.
     * It stops the server thread, closes the server socket, and exits the application.
     */
    private void closeServer(){
        isRunning = false;
        try {
            serverSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        Platform.exit();
        System.exit(0);
    }

    /**
     * Combines the user activity output, active client list, and exit area into a single layout.
     * The user activity output and active client list are placed at the top, 
     * while the exit area is placed at the bottom.
     * 
     * @param userActivityOutput The VBox containing the user activity log.
     * @param activeClientBox The VBox containing the active client list.
     * @param exitArea The HBox containing the exit button.
     * @return A BorderPane containing the combined layout of the server GUI.
     */

    private BorderPane combineAreas(VBox userActivityOutput, VBox activeClientBox, HBox exitArea){
        HBox ClientAreaBox = new HBox(userActivityOutput, activeClientBox);

        BorderPane backGroundPane = new BorderPane();
        backGroundPane.setTop(ClientAreaBox);
        backGroundPane.setBottom(exitArea);
        backGroundPane.setStyle("-fx-background-color: rgb(40, 45, 50)");
        
        return backGroundPane;
    }
    
    /**
     * Adds a client to the active client list and user activity log.
     * This method is called by the Server class when a new client connects
     * to the server. It adds the client to the active client list and
     * appends a message to the user activity log indicating that the client
     * has connected.
     * @param username The username of the client to add
     */
    private void addClientGui(String username) {
        Platform.runLater(() -> {
            activeClientList.getItems().add(username);
            userActivityOutput.appendText(username + " has connected to the server.\n");
        });
    }

    /**
     * Removes a client from the active client list and user activity log.
     * This method is called by the Server class when a client disconnects
     * from the server. It removes the client from the active client list and
     * appends a message to the user activity log indicating that the client
     * has disconnected.
     * @param username The username of the client to remove
     */
    public void removeClientGui(String username) {
        Platform.runLater(() -> {
            activeClientList.getItems().remove(username);
            userActivityOutput.appendText(username + " has disconnected from the server.\n");
        });
    }

    /**
     * Prints a message to the user activity log in the GUI.
     * This method is thread-safe and can be called by any thread.
     * @param msg The message to print to the user activity log
     */
    public void printUserActivity(String msg) {
        Platform.runLater(() -> userActivityOutput.appendText(msg + "\n"));
    }
    
    /**
     * Launches the Server GUI.
     * @param args Command-line arguments passed to the application
     */
    public static void main(String[] args) {
        launch(args);
    }
}