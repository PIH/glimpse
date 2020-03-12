package org.pih.petl.job.type;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.time.StopWatch;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.pentaho.di.core.KettleEnvironment;
import org.pentaho.di.core.Result;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.logging.FileLoggingEventListener;
import org.pentaho.di.core.logging.KettleLogStore;
import org.pentaho.di.core.logging.LogLevel;
import org.pentaho.di.job.JobMeta;
import org.pih.petl.ApplicationConfig;
import org.pih.petl.PetlException;
import org.pih.petl.api.ExecutionContext;
import org.pih.petl.job.PetlJob;
import org.pih.petl.job.config.PetlJobConfig;

/**
 * This class is responsible for running a Pentaho Kettle PetlJob
 */
public class PentahoJob implements PetlJob {

    private static final Log log = LogFactory.getLog(PentahoJob.class);

    //******** CONSTANT CONFIGURATION PARAMETERS *******

    public static final String JOB_FILE_PATH = "job.filePath";
    public static final String JOB_LOG_LEVEL = "job.logLevel";

    /**
     * Creates a new instance of the job
     */
    public PentahoJob() {
    }

    /**
     * @see PetlJob
     */
    @Override
    public void execute(final ExecutionContext context) throws Exception {

        ApplicationConfig appConfig = context.getApplicationConfig();
        PetlJobConfig jobConfig = context.getJobConfig();
        Properties configuration = jobConfig.getAsProperties();

        // TODO: Add validation in
        String jobFilePath = configuration.getProperty(JOB_FILE_PATH);
        log.info("PetlJob file path: " + jobFilePath);
        File jobFile = appConfig.getConfigFile(jobFilePath).getConfigFile();

        /*
        First, we want to create an execution environment for this job to set up Kettle with appropriate properties
        TODO:  Consider removing this in later phases, but we will need to change the pipeline code accordingly.
        We create a subdirectory under work/{{jobId}}/.kettle, and set KETTLE_HOME to point to this.
        This gives us somewhere for this execution, where we can write kettle.properties and pih-kettle.properties,
        so that the pipeline can find and use these as currently designed.
        */
        File workDir = ensureDir(appConfig.getPetlHomeDir(), "work");
        File jobDir = ensureDir(workDir, context.getJobExecution().getUuid());
        File kettleDir = ensureDir(jobDir, ".kettle");
        System.setProperty("KETTLE_HOME", jobDir.getAbsolutePath());

        try {
            // Configure kettle.properties with PIH_PENTAHO_HOME which we aquire from the configuration
            File srcDir = appConfig.getPetlHomeDir();
            if (!srcDir.exists()) {
                throw new PetlException("Unable to initialize kettle environemnt.  Unable to find: " + srcDir);
            }
            try {
                Properties kettleProperties = new Properties();
                kettleProperties.put("PIH_PENTAHO_HOME", srcDir.getAbsolutePath());
                kettleProperties.store(new FileWriter(new File(kettleDir, "kettle.properties")), null);
                log.info("Wrote kettle.properties to " + kettleDir);
            }
            catch (IOException e) {
                throw new PetlException("Unable to initialize kettle.  Error writing to kettle.properties.", e);
            }

            // Initialize the Kettle environment
            try {
                log.info("Initializing Kettle Environment");
                log.info("KETTLE_HOME = " + System.getProperty("KETTLE_HOME"));
                KettleEnvironment.init();
            }
            catch (KettleException e) {
                throw new PetlException("Unable to initialize kettle environment.", e);
            }

            log.info("CONFIGURATION:");

            Set<String> propertyNames = configuration.stringPropertyNames();
            for (String property : propertyNames) {
                String propertyValue = configuration.getProperty(property);
                if (property.toLowerCase().contains("password")) {
                    propertyValue = "************";
                }
                log.info(property + " = " + propertyValue);
            }

            // Write passed configuration properties to pih-kettle.properties

            try {
                configuration.store(new FileWriter(new File(kettleDir, "pih-kettle.properties")), null);
                log.info("Wrote pih-kettle.properties to " + kettleDir);
            }
            catch (IOException e) {
                throw new PetlException("Unable to initialize kettle.  Error writing to pih-kettle.properties.", e);
            }

            // Load job path, log level, and set named parameters based on configuration properties

            JobMeta jobMeta = new JobMeta(jobFile.getAbsolutePath(), null);

            log.info("PetlJob parameters: ");
            String[] declaredParameters = jobMeta.listParameters();
            for (int i = 0; i < declaredParameters.length; i++) {
                String parameterName = declaredParameters[i];
                String parameterValue = jobMeta.getParameterDefault(parameterName);
                if (configuration.containsKey(parameterName)) {
                    parameterValue = configuration.getProperty(parameterName);
                }
                log.info(parameterName + " -> " + parameterValue);
                jobMeta.setParameterValue(parameterName, parameterValue);
            }

            String logLevelConfig = configuration.getProperty(JOB_LOG_LEVEL, "MINIMAL");
            LogLevel logLevel = LogLevel.valueOf(logLevelConfig);
            log.info("PetlJob log level: " + logLevel);

            org.pentaho.di.job.Job job = new org.pentaho.di.job.Job(null, jobMeta);
            job.setLogLevel(logLevel);

            StopWatch stopWatch = new StopWatch();
            stopWatch.start();

            log.info("Starting PetlJob Execution...");

            FileLoggingEventListener logger = setupLogger(context, job);
            job.start();  // Start the job thread, which will execute asynchronously
            job.waitUntilFinished(); // Wait until the job thread is finished
            shutdownLogger(logger);

            stopWatch.stop();

            Result result = job.getResult();
            // TODO: We can inspect the result here and take action (eg. notify on errors, etc)

            log.info("***************");
            log.info("PetlJob executed in:  " + stopWatch.toString());
            log.info("PetlJob Result: " + result);
            log.info("***************");
        }
        finally {
            try {
                FileUtils.deleteDirectory(jobDir);
            }
            catch (Exception e) {
                log.warn("Error deleting job directory: " + jobDir);
            }
        }
    }

    public FileLoggingEventListener setupLogger(ExecutionContext context, org.pentaho.di.job.Job job) {
        File logFile = context.getApplicationConfig().getLogFile();
        try {
            FileLoggingEventListener fileLogListener = new FileLoggingEventListener(job.getLogChannelId(), logFile.getAbsolutePath(), true);
            KettleLogStore.getAppender().addLoggingEventListener(fileLogListener);
            return fileLogListener;
        }
        catch (Exception e) {
            throw new PetlException("Error setting up logging to " + logFile, e);
        }
    }

    public void shutdownLogger(FileLoggingEventListener loggingEventListener) {
        try {
            KettleLogStore.getAppender().removeLoggingEventListener(loggingEventListener);
            loggingEventListener.close();
        }
        catch (KettleException e) {
            throw new PetlException("Unable to shutdown logger", e);
        }
    }

    public File ensureDir(File parent, String dirName) {
        File f = new File(parent, dirName);
        f.mkdirs();
        return f;
    }
}
