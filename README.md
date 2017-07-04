# Detection of locally anomalous purchases in a social network
### Insight Data Engineering Coding Challenge 2017
Ramraj Velmurugan (ramraj@gmail.com)
## Execution
The Java code (one file) can be compiled and executed by running run.sh as required by the challenge. The program is made up of one class (with some subclasses to hold the various data structures). The program takes three arguments, which are the paths to the two input files batch_input.json and stream_input.json and the output file flagged_purchases.json. The shell script will also identify if the only external library Google's Gson json reader is present and if not will curl it.

## Imports
The only external import is the Gson library since it's best to let an established library perform the parsing of json data. The shell script should automatically download it if the jar file is not already present. 

## Implementation
The overall flow of the program is as follows:

1. The main() function calls the readJsonStream() function for both the batch and stream log files, with options to only check for anomalies in the latter file.

2. The readJsonStream() function reads the json files one line at a time and tries to load the data into a JsonEvent object. It then categorizes the type of data (4 types - D/T specification, befriending, unfriending and purchasing). Appropriate functions are then used to load the data into an ArrayList of User objects (which also contain the Purchase objects as ArrayLists in each User object) where the index of the object also conveniently serves as the user_id. 

3. The readJsonStream() also calls the checkForAnomaly() function for each encountered purchase when dealing with the stream_log.json file.

4. The checkForAnomaly() function first forms a friend network of the *D*th degree with the help of a UserNetworkBuilder object and gets the *T* most recent purchases from this network (with sorting performed by a custom Comparator object that implements the sorting rule specified in the project).

5. It then identifies if the purchase amount is anomalous with the help of a MeanSTDCutoff object. If the purchase is found to be anomalous, the data is written to the output file in the appropriate json format. 

## Salient features

* The program was written in Java to attempt the best compromise between speed, understandability, customizability, and reliability. The program does struggle to scale reliably with higher D values, though such a problem may be expected with any implementation of a solution to the exact given problem (which is to recompute the network everytime a friending/unfriending event is encountered) especially without the use of database engines.  

* The process has been compartmentalized with keeping future updates to the algorithm in mind: given that this is a data analytics program, we can easily expect the manager to come back with further requests to modify the computation of the metrics. Hence sub-classes and functions have been created so that such potential modifications may be made easily.

* The current implementation of the program can handle two network models. The default setting of the *NETWORK_MODEL* constant allows the program to run as per specification and includes all the purchases made by the users in the given network, without consideration for the time at which the purchases were made. Switching the value of the *NETWORK_MODEL* constant would consider the time the purchase was made by each friend - if the friend made a purchase before befriending someone, that purchase will not be counted as part of the anomaly calculation. 

* The program has been designed to be as robust to variable input as possible, but some assumptions have been made:

    * The D and T values will ideally have to be assigned before the beginning of the stream_log.json file. 

    * The json format follows more or less the same strict format as given in the examples (to the extent that the Gson library can read it without errors). 

    * It is safe to skip lines of json input where the data cannot be parsed correctly (checks have been included in places where this could cause a catastrophic error, such as future unfriending events between friends that were never recorded). 

## Performance
The program consistently completes the anomaly check for the given medium-sized file in less than one second on an entry-level Macbook Pro (total execution including base network calculation takes ~3 seconds). Increasing D value to 3 takes 8 seconds and continues to scale worse with higher D values. Given that the network becomes very large even with a D value of 2 (the average network size for the given medium-sized example was 336 and contained on average 13,466 purchases) and how the effect of individual friending and unfriending events will matter less in such large networks, other implementation changes should be considered if higher D values need to be regularly computed.

## Error handling
The program tries to handle errors smoothly in several places:

1. If the files are not found in the paths specified, it exits gracefully (what else can we do?)

2. If individual lines of the json file are not parsed correctly, a message is displayed showing the json string that failed to parse. The execution continues. 

3. Bad data is handled in several ways:

    * If data is so malformed as to not even make sense (characters in number fields, date format gone wrong, etc), it gets classified under the json parse error umbrella above.

    * If data is parsed but still wrong, they get added anyway:
          * Wrong dates get added as wrong dates

          * User ids, if they don't exist, get added as new user ids.

          * If friendships are to be made between users that don't exist, the user ids are created.

          * If an unfriending event is called to a user that doesn't exist, then a warning message is shown but the program continues execution. 

          * If a purchase is added to a user id that doesn't exist, it is created and then added to it.
    
 4. All non-fatal errors are logged into an error_log.txt file; if more than 25 non-fatal errors are generated, they are not displayed anymore but only logged in the error file.

## Testing
The code was tested with >10 different test cases; the algorithm was quickly remade with MATLAB to generate test cases and results and crosschecked. The repository was pulled onto a Cloud9 dev environment to verify that it runs smoothly in a Linux environment.