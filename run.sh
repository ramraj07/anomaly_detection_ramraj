#!/bin/bash
if [ ! -f ./src/gson-2.6.2.jar ]; then
printf "\n\ngson-2.6.2.jar required. Curling...\n\n"
curl -o ./src/gson-2.6.2.jar "http://central.maven.org/maven2/com/google/code/gson/gson/2.6.2/gson-2.6.2.jar"
fi
printf "Compiling...\n"
javac -cp ./src/gson-2.6.2.jar ./src/AnomalousPurchaseDetector.java
printf "Executing...\n"
java -cp ./src/gson-2.6.2.jar:./src AnomalousPurchaseDetector ./log_input/batch_log.json ./log_input/stream_log.json ./log_output/flagged_purchases.json