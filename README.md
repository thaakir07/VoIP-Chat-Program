# VoIP Chat Program

A Voice over Internet Protocol (VoIP) chat application built in Java that enables real-time voice communication, text messaging, and voice note sharing between multiple clients. This project implements both server and client components with a comprehensive GUI for seamless user interaction.

## Group Members & Contributions

**Group 7**
- **Thaakir Fernandez** (Group Leader) - thaakir07@gmail.com
- **Priyal Bhana**
- **Gideon Daniel Botha**
- **Sulaiman Bandarkar**

- ## Project Timeline

- **Start Date:** April 2025
- **Completed:** May 2025

## Features

### Core Functionality
- **Real-time Voice Calls**: Private and group voice calls using UDP transmission
- **Global Text Chat**: Broadcast messages to all connected users
- **Private Messaging**: Direct whisper functionality between users
- **Group Chat**: Custom group creation with personalized names
- **Voice Notes**: Record, send, and play pre-recorded voice messages
- **User Management**: Real-time user list with connection status

### Audio Features
- **High-Quality Audio**: 16kHz sample rate, 16-bit mono audio for calls
- **Jitter Buffer**: Packet reordering for smooth audio playback
- **Audio Mixing**: Multiple participant audio mixing for group calls
- **Voice Note Recording**: 8kHz WAV format for efficient file transfer

### Technical Features
- **Multi-threaded Architecture**: Separate threads for audio processing
- **TCP/UDP Hybrid**: TCP for reliable messaging, UDP for real-time audio
- **Direct P2P Audio**: Client-to-client audio transmission
- **Resource Management**: Automatic cleanup of temporary files and connections

## Prerequisites

- JavaFX libraries (if not included in your JDK)
- Operating system with audio input/output capabilities
- Network connectivity for client-server communication

## How to compile & execute our program

The following are the commands used to compile and execute our program via the 
terminal using the Makefile:

1. **Compiles all java files in src.**
   ```
   *make compile
   ```

2. **Compiles the Server.java file in src, and then runs it**
   ```
   make server
   ```

3. **Compiles the Client.java file in src, and then runs it.**
   ```
   make client
    ```
   
4. **Removes all *.class files.**
   ```
   make clean
    ```

### Using the Application

#### Text Communication
- **Global Chat**: Type messages in the main chat area
- **Private Messages**: Select user from list, send whisper messages

#### Voice Calls
- **Private Calls**: Click "Call" button in whisper window
- **Group Calls**: Initiate calls with multiple participants
- **Call Controls**: Start/stop call functionality with GUI buttons

#### Voice Notes
- **Recording**: Click "Start Voicenote" to begin recording
- **Sending**: Voice notes automatically sent after stopping recording
- **Playback**: Received voice notes saved to `received_voice_notes/` directory

## Contributing

This project was developed as part of an academic assignment. The codebase builds upon previous projects including:
- Project 1: Client-Server Chat Program
- Project 2: Reliable Blast User Datagram Protocol (RBUDP)

## License

This project is part of an academic assignment and is intended for educational purposes.
