# Simple Makefile for VoIP Chat Application

JAVAFX_HOME=~/javafx/javafx-sdk-23.0.2
SRC=src
BIN=bin

# Compile and run commands with JavaFX
JAVAC=javac --module-path $(JAVAFX_HOME)/lib --add-modules javafx.controls
JAVA=java --module-path $(JAVAFX_HOME)/lib --add-modules javafx.controls -cp $(BIN)

# Compile all files
compile:
	mkdir -p $(BIN)
	$(JAVAC) -d $(BIN) $(SRC)/*.java

# Run server
server: compile
	$(JAVA) ServerGui

# Run client
client: compile
	$(JAVA) ClientGui

# Clean up
clean:
	rm -rf $(BIN)
