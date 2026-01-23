#!/bin/bash

# Get the directory where this script resides
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# Define the path to the shaded JAR
JAR_PATH="$SCRIPT_DIR/target/mkpro-1.4-SNAPSHOT-shaded.jar"

# Check if the JAR exists
if [ ! -f "$JAR_PATH" ]; then
    echo "Error: mkpro JAR not found at $JAR_PATH"
    echo "Please run 'mvn package -DskipTests' first."
    exit 1
fi

# Run the application
java -jar "$JAR_PATH" "$@"
