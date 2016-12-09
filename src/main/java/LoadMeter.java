/**
 * Written by Gil Tene of Azul Systems, and released to the public domain,
 * as explained at http://creativecommons.org/publicdomain/zero/1.0/
 *
 * @author Gil Tene
 */

import org.jhiccup.Version;
import org.jhiccup.HiccupMeter;
import org.jhiccup.internal.hdrhistogram.*;


import java.io.*;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

public class LoadMeter extends HiccupMeter {
    static final String defaultLoadMeterLogFileName = "loadmeter.%date.%pid";

    final String versionString = "LoadMeter version " + Version.version;


    RandomAccessFile statFile;
    Scanner scanner;

    public LoadMeter(final String[] args, String defaultLogFileName) throws FileNotFoundException {
        super(args, defaultLogFileName);
        try {
            statFile = new RandomAccessFile("/proc/stat", "r");
        } catch (FileNotFoundException e) {
            try {
                statFile = new RandomAccessFile("proc/stat", "r");
            } catch (FileNotFoundException ex) {
                System.err.println("LoadMeter: Failed to open both /proc/stat and proc/stat");
                System.exit(-1);
            }
        }
        scanner = new Scanner(statFile.getChannel());
    }

    @Override
    public HiccupRecorder createHiccupRecorder(final SingleWriterRecorder recorder) {
        return new LoadRecorder(recorder);
    }

    @Override
    public String getVersionString() {
        return versionString;
    }

    class LoadRecorder extends HiccupRecorder {

        LoadRecorder(final SingleWriterRecorder recorder) {
            super(recorder, false);
            this.setName("LoadRecorder");
        }

        @Override
        public void run() {
            final long resolutionNsec = (long)(config.resolutionMs * 1000L * 1000L);
            try {
                while (doRun) {
                    if (config.resolutionMs != 0) {
                        TimeUnit.NANOSECONDS.sleep(resolutionNsec);
                    }

                    long load = getLoad();
                    recorder.recordValue(load * 1000000L);
                }
            } catch (InterruptedException e) {
                if (config.verbose) {
                    log.println("# LoadRecorder interrupted/terminating...");
                }
            }
        }
    }

    long getLoad() {
        // return ((counter++) % 100 == 0) ? (((counter / 100) % 16) + 1) : 1;
        try {
            statFile.seek(0);
            scanner.reset();

            while (scanner.hasNextLine()) {
                try {
                    if (scanner.next().equals("procs_running")) {
                        return scanner.nextLong();
                    }
                } catch (java.util.NoSuchElementException e) {
                    return 0;
                }
            }
        } catch (IOException ex) {
            return 0;
        }

        return 0;
    }

    public static LoadMeter commonMain(final String[] args, boolean exitOnError) {
        LoadMeter loadMeter = null;
        Thread.currentThread().setName("LoadBookkeeper ");
        try {
            loadMeter = new LoadMeter(args, defaultLoadMeterLogFileName);

            if (loadMeter.config.error) {
                if (exitOnError) {
                    System.exit(1);
                } else {
                    throw new RuntimeException("Error: " + loadMeter.config.errorMessage);
                }
            }

            if (loadMeter.config.verbose) {
                loadMeter.log.print("# Executing: LoadMeter");
                for (String arg : args) {
                    loadMeter.log.print(" " + arg);
                }
                loadMeter.log.println("");
            }

            // We currently represent load in multiples of 1,000,000x in order to fit into
            // the way latency logs like to report (msec units for nsec contents). This
            // has the nasty side effect of showing non-whole integer values for load due
            // to the histogram's bucket-boundary rounding at that level (and we really don't
            // want to require 6 decimal point of accuracy in the histogram just for this).
            //
            // What we really want is for values in output to represent number of runnable
            // threads as integers:
            // loadMeter.config.outputValueUnitRatio = 1.0;
            //
            // We'll bring this back when we get an API for maxValueOutputRatio in HistogramLogWriter...

            loadMeter.start();

        } catch (FileNotFoundException e) {
            System.err.println("LoadMeter: Failed to open log file.");
        }
        return loadMeter;
    }

    public static void premain(String argsString, java.lang.instrument.Instrumentation inst) {
        final String[] args = ((argsString != null) && !argsString.equals("")) ? argsString.split("[ ,;]+") : new String[0];
        final String avoidRecursion = System.getProperty("org.jhiccup.avoidRecursion");
        if (avoidRecursion != null) {
            return; // If this is a -c invocation, we do not want the agent to do anything...
        }

        // We want agent launches to fork off a separate process that will co-terminate with this one:

        HiccupMeterConfiguration config = new HiccupMeterConfiguration(args, defaultLoadMeterLogFileName);
        String execCommand =
                System.getProperty("java.home") +
                        File.separator + "bin" + File.separator + "java" +
                        " -cp " + System.getProperty("java.class.path") +
                        " -Dorg.jhiccup.avoidRecursion=true" +
                        " " + LoadMeter.class.getCanonicalName() +
                        " -l " + config.logFileName +
                        " -i " + config.reportingIntervalMs +
                        " -d " + config.startDelayMs +
                        ((config.startTimeAtZero) ? " -0" : "") +
                        ((config.logFormatCsv) ? " -o" : "") +
                        " -s " + config.numberOfSignificantValueDigits +
                        " -r " + config.resolutionMs +
                        " -terminateWithStdInput";

        new ExecProcess(execCommand, "LoadMeterProcess", System.err, config.verbose);
    }

    public static void main(final String[] args)  {
        final LoadMeter loadMeter = commonMain(args, true /* exit on error */);

        if (loadMeter != null) {
            // The HiccupMeter thread, on it's own, will not keep the JVM from exiting. If nothing else
            // is running (i.e. we we are the main class), then keep main thread from exiting
            // until the HiccupMeter thread does...
            try {
                loadMeter.join();
            } catch (InterruptedException e) {
                if (loadMeter.config.verbose) {
                    loadMeter.log.println("# LoadMeter main() interrupted");
                }
            }
        }
    }
}
