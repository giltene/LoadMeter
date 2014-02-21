LoadMeter
=========

A tool that records instantaneous linux load (runnabel thread count) in 1mec intervals and logs it
in jHiccup-like format

run "java -jar target/LoadMeter.jar -h" for options.

LoadMeter can be run directly, as in:

java -jar target/LoadMeter.jar -d 0 -i 1000 -t 50000

Or it can be run as a java agent, as in:

java -javaagent:target/LoadMeter.jar="-d 0 -i 1000" myJavaProg ...

When run as an agent, LoadMeter will fork off as a separate process, to make sure it is not affected by jvm-internal
noise, but will terminate when the launching process does. This is useful for synchronizing log file lengths and
times with other logging tools (such as jHiccup).

