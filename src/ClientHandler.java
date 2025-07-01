import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

/*
 * This class represents a client that connects to a server and sends and
 * receives messages. It handles private messages, group messages, and
 * broadcast messages between clients.
 */
public class ClientHandler implements Runnable {
    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;
    private String username;
    private ConcurrentHashMap<String, ClientHandler> clientList;
    private ConcurrentHashMap<String, String> IPlist;
    public ConcurrentHashMap<String, String[]> groupList = new ConcurrentHashMap<String, String[]>();
    private ServerGui server;

    /**
    * The constructor for this class.
    * @param socket The socket that the client is connected to.
    * @param clientList The list of connected clients.
    * @param username The username of the client.
    * @param IPlist The list of IP addresses of connected clients.
    * @param server The server that the client is connected to.
    */
    public ClientHandler(Socket socket, ConcurrentHashMap<String, ClientHandler> clientList,
    String username, ConcurrentHashMap<String, String> IPlist, ServerGui server) {
        try {
            this.socket = socket;
            this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            this.username = username;
            this.clientList = clientList;
            this.IPlist = IPlist;
            this.server = server;
        } catch (IOException e) {
            communicate("terminate");
        }
    }

    /**
     * The main loop of the client handler. Listens for incoming messages from
     * the client, and broadcasts them to all other connected clients.
     */
    public void run() {
        String messageFromClient;
        while (socket.isConnected()) {
            try {
                messageFromClient = reader.readLine();
                broadcastMessage(messageFromClient);
            } catch (IOException e) {
                communicate("terminate");
                break;
            }
        }
    }

    /**
     * Broadcasts a message from the client to all other connected clients.
     * This method handles private messages, group messages, and the /exit
     * command.
     * @param message The message from the client.
     */
    public void broadcastMessage(String message) {
        if (message == null) {
            return;
        }
        String actualMessage = message.trim();
        if (actualMessage.equals("/exit")) {
            communicate("Exiting chat...");
            removeClient();
            return;
        } else if (actualMessage.startsWith("@") && actualMessage.indexOf(" ") != -1) {
            whisper(actualMessage);
            server.printUserActivity(username + " sent a private message");
        } else if (actualMessage.startsWith("/creategroup")) {
            createGroup(actualMessage);
            server.printUserActivity(username + " created a group");
        } else if (actualMessage.startsWith("/groupmsg")) {
            groupMessage(actualMessage);
            server.printUserActivity(username + " sent a group message");
        } else if (actualMessage.startsWith("/getIps")) {
            getIps(actualMessage);
        } else if (actualMessage.startsWith("Call")) {
            initiateCall(actualMessage);
            String receiver = actualMessage.substring(actualMessage.indexOf(" ") + 1);
            server.printUserActivity(username + " initiated a call with " + receiver);
        } else if (actualMessage.startsWith("CALL ENDED")) {
            hangup(actualMessage);
            server.printUserActivity(username + " ended a call");
        } else {
            groupChat(message, false);
            server.printUserActivity(username + " sent a global message");
        }
        return;
    }

    /**
     * Sends a message to all connected clients except the sender, simulating
     * a group chat.
     * @param message The message to be sent to other clients.
     * @param bool is just used to solve an edge case with the @prefix
     */
    public void groupChat(String message, boolean bool) {
        //Deals with an edge case with the '@' prefix
        if (message.startsWith("@")) {
            communicate("No message attached");
            return;
        }
        //Accessing the individual clients' handlers and using them to broadcast the message was the cleanest way
        // to implement the feature in our humble opinion
        for (ClientHandler client : clientList.values()) {
            if (!client.username.equals(username)) {
                synchronized (client) {
                    try {
                        if (bool) {
                            //Deals with an edge case with regards to clients leaving and joining
                            client.writer.write(message);
                        } else {
                            //Normal group chat message
                            client.writer.write(username + ": " + message);
                        }
                        client.writer.newLine();
                        client.writer.flush();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

/**
 * Sends a private message to a specified client. The message must be prefixed
 * with the '@' symbol followed by the recipient's username and a space.
 * @param message The message format should be "@username message" where
 *                "username" is the recipient's username and "message" is the
 *                content to be delivered.
 */
    public void whisper (String message) {
        if (message.indexOf(" ") == -1) {
            communicate("No message attached");
            return;
        }

        String receiver = message.substring(1, message.indexOf(" "));
        message = message.substring(message.indexOf(" ") + 1);
        ClientHandler client = clientList.get(receiver);
        //Deal with any edge cases
        if (client == null) {
            communicate("Client not found");
        } else if (message.equals("")) {
            communicate("No message attached");
        } else {
            //Send the message to the receiver, again synchrozing to avoid race conditions etc.
            synchronized (client) {
                try {
                    client.writer.write("Whisper from " + this.username + ": " + message);
                    client.writer.newLine();
                    client.writer.flush();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }   

    /**
     * Removes the client from the ConcurrentHashMap and broadcasts a message to
     * all other clients that the client has left the group chat. This method is
     * called when the client sends the /exit command or when the client's
     * connection is terminated.
     */
    public void removeClient() {
        String exitMessage = username + " has left the group chat.";
        String leavingmsg = "LEAVING: " + username;
        synchronized (clientList) {
            clientList.remove(username);
            server.removeClientGui(username);
        }
        synchronized (IPlist) {
            IPlist.remove(username);
        }

        //We send 2 messages because one lets the clients know to remove the former client locally
        groupChat(leavingmsg, true);
        groupChat(exitMessage, true);
        communicate("terminate");
    }

    /**
     * Sends a communication message to the client through the output stream.
     * This method writes the message, adds a newline, and flushes the stream
     * to ensure the message is sent.
     * @param comms The message to be sent to the client.
     */
    public void communicate(String comms) {
        try {
            writer.write(comms);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    //################### added methods #################
    
    /**
     * Handles a call command from the client.
     * If the target is a named group chat, sends a CALL ACCEPTED message to all clients in the group chat.
     * If the target is a private call, sends a CALL ACCEPTED message to the target client.
     * If the target is a global call, sends a CALL ACCEPTED message to all other clients.
     * @param message The call command from the client.
     */
    public void initiateCall(String message) {
        String targetUsername = message.substring(5).trim();
        ClientHandler targetClient = clientList.get(targetUsername);
        List<String> targetIPs = new ArrayList<>();
        String senderIP = IPlist.get(username);
        int port = 5001;

        // Use different ports for sender and receiver to avoid collision
        

        // global group call
        if (targetUsername.equalsIgnoreCase("global")) {
            for (ClientHandler client : clientList.values()) {
                String ip = IPlist.get(client.username);
                if (ip != null) {
                    targetIPs.add(ip +": " + port);
                    port++;
                }
            }
    
            if (!targetIPs.isEmpty()) {
                String joinedIPs = String.join(",", targetIPs);
                for (ClientHandler client : clientList.values()) {
                    client.communicate("CALL ACCEPTED (global): " + joinedIPs);
                }
                //communicate("CALL ACCEPTED (global): " + joinedIPs);
            } else {
                communicate("CALL FAILED: No other users online");
            }
            return;
        } 
        
        // named group chat call
        if (groupList.contains(targetUsername)) {
            String[] membersArray = groupList.get(targetUsername);
            for (String member : membersArray) {
                ClientHandler client = clientList.get(member);
                if (client != null && !client.username.equals(username)) {
                    String ip = IPlist.get(client.username);
                    if (ip != null) {
                        targetIPs.add(ip);
                        client.communicate("CALL ACCEPTED: " + senderIP);
                    }
                }
            }
            if (!targetIPs.isEmpty()) {
                String joinedIPs = String.join(",", targetIPs);
                communicate("CALL ACCEPTED: " + joinedIPs);
            } else {
                communicate("CALL FAILED: No other users online");
            }
            return;
        }

        // private call
        if (targetClient != null) {
            String targetIP = IPlist.get(targetUsername);
            targetClient.communicate("CALL ACCEPTED (private): " + senderIP + ":" + port + ":" + username);
            communicate("CALL ACCEPTED (private): " + targetIP + ":" + ++port + ":" + targetUsername);
        
        } else {
            communicate("CALL FAILED: User not found");
        }
    }
    
    /**
     * Handles a hangup command from the client.
     * If the target is a private call, sends a CALL ENDED message to the target client.
     * If the target is a group call or global call, does nothing.
     * @param message The hangup command from the client.
     */
    public void hangup(String message) {
        String targetName = message.substring(message.indexOf(":") + 1).trim();
        if (targetName.contains("global") || groupList.containsKey(targetName)) {
            return;
        }
        ClientHandler targetClient = clientList.get(targetName);

        // private call hangup
        if (targetClient == null) {
            communicate("PRIVATE CALL HANGUP FAILED: User not found");
        } else {
            targetClient.communicate("CALL ENDED:" + username);
        }
    }

    /**
     * Handles a create group command from the client.
     * Breaks the command into a group name and a list of member usernames.
     * Adds the group to the groups HashMap for the creator.
     * Pushes a notification to all group members that a group needs to be built.
     * @param message The create group command from the client.
     */
    private void createGroup(String message) {
        // Break the message into its parts
        String groupName = message.substring(message.indexOf("@") + 1, message.indexOf("-"));
        String membersString = message.substring(message.indexOf("-") + 1);
        String[] membersArray = membersString.split(",");

        // Add new group to the groups HashMap for creator
        groupList.put(groupName, membersArray);

        // Push notification that a group needs to be built to all group members
        for (String member : membersArray) {
            ClientHandler client = clientList.get(member);
            if (client != null && !client.username.equals(username)) {
                client.groupList.put(groupName, membersArray);
                client.communicate("Join Group: "  + "@" + groupName + "-" + membersString);
            }
        }
    }

    /**
     * Handles a group message from the client.
     * Breaks the message into its parts and communicates the message to all group members.
     * @param message The group message from the client.
     */
    private void groupMessage(String message) {
        // Break the message into its parts
        String groupName = message.substring(message.indexOf("@") + 1, message.indexOf("-"));
        if (!groupList.containsKey(groupName)) {
            return; // Do nothing if the group does not exist
        } else {
            String[] membersArray = groupList.get(groupName);
            for (String member : membersArray) {
                ClientHandler client = clientList.get(member);
                if (client != null && !client.username.equals(username)) {
                    client.communicate("Group message from " + "/" + username + ": " + "@" + groupName 
                        + "-" + message.substring(message.indexOf("-") + 1));
                }
            }
        }
    }

    /**
     * Handles a getIps command from the client.
     * If the command is "/getIps @Global", it sends a list of all online client IPs.
     * If the command is "/getIps @<groupname>", it sends a list of all IPs in the group.
     * If the command is "/getIps <username>", it sends the IP of the specified client.
     * @param message The getIps command from the client.
     */
    private void getIps(String message) {
        String ipList = "";
        if (message.equals("/getIps @Global")) {
            for (ClientHandler client : clientList.values()) {
                if (client.username.equals(username)) {
                    continue;
                } else {
                    String ip = IPlist.get(client.username);
                    ipList += ip + ",";
                }
            }
            ipList = ipList.substring(0, ipList.length() - 1);
            communicate("receivedIPs voicenote:" + ipList + "@Global");
        } else if (message.startsWith("/getIps @")) {
            String groupName = message.substring(message.indexOf("@") + 1);
            String[] membersArray = groupList.get(groupName);
            for (String member : membersArray) {
                if (member.equals(username)) {
                    continue;
                } else {
                    String ip = IPlist.get(member);
                    ipList += ip + ",";
                }
            }
            ipList = ipList.substring(0, ipList.length() - 1);
            communicate("receivedIPs voicenote:" + ipList + "@" + groupName);
        } else {
            String receiverName = message.substring(message.indexOf(" ") + 1);
            ClientHandler client = clientList.get(receiverName);
            if (client != null) {
                String ip = IPlist.get(receiverName);
                communicate("receivedIPs voicenote:" + ip + "@" + receiverName);
            }
        }
    }
}
