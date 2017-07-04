# Detection of locally anomalous purchases in a social network
### Insight Data Engineering Coding Challenge 2017
Ramraj Velmurugan (ramraj@gmail.com)
## Execution
The Java code (one file) can be compiled and executed by running run.sh as required by the challenge. The program is made up of one class (with some subclasses to hold the various data structures). The program takes three arguments, which are the paths to the two input files batch_input.json and stream_input.json and the output file flagged_purchases.json. The shell script will also identify if the only external library Google's Gson json reader is present and if not will <code>curl</code> it.