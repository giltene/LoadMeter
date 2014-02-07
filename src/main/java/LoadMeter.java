//import org.HdrHistogram.Histogram;
import org.jhiccup.Version;
import org.jhiccup.HiccupMeter;
import org.jhiccup.internal.hdrhistogram.Histogram;

import java.io.*;
import java.util.Scanner;

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
    public HiccupRecorder createHiccupRecorder(Histogram initialHistogram) {
        return new LoadRecorder(initialHistogram);
    }

    @Override
    public String getVersionString() {
        return versionString;
    }

    class LoadRecorder extends HiccupRecorder {

        LoadRecorder(final Histogram histogram) {
            super(histogram, false);
            this.setName("LoadRecorder");
        }

        @Override
        public void run() {
            long counter = 0;
            try {
                while (doRun) {
                    if (config.resolutionMs != 0) {
                        Thread.sleep(config.resolutionMs);
                    }

                    long load = getLoad() * 1000;
                    histogram.recordValue(load);

                    if (newHistogram != null) {
                        // Someone wants to replace the running histogram with a new one.
                        // Do wait-free swapping. The recording loop stays wait-free, while the other side polls:
                        final Histogram tempHistogram = histogram;
                        histogram = newHistogram;
                        newHistogram = null;
                        // The requesting thread will observe oldHistogram through polling:
                        oldHistogram = tempHistogram;
                        // Signal that histogram was replaced:
                        histogramReplacedSemaphore.release();
                    }
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

            loadMeter.config.outputValueUnitRatio = 1000.0;

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
