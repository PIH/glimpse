package org.pih.petl.job.schedule;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.pih.petl.api.EtlService;
import org.pih.petl.api.JobExecution;
import org.pih.petl.job.config.PetlJobConfig;
import org.quartz.CronExpression;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Map;

/**
 * Schedulable task for loading all of the configurations
 */
@Component
public class PetlScheduledExecutionTask implements Job {

    private static final Log log = LogFactory.getLog(PetlScheduledExecutionTask.class);

    private static boolean enabled = true;
    private static boolean inProgress = false;

    @Autowired
    EtlService etlService;

    /**
     * Load all jobs from file system
     * For each job found:
     *  - Get the job path and query DB for last execution start and end date
     *  - If it has never been executed, then execute it
     *  - Else if it is currently executing, then skip
     *  - Else, if there is a cron expression configured, check if next scheduled date following
     *    last execution date is <= now, and if so, execute it
     */
    @Override
    public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
        if (enabled && !inProgress) {
            try {
                inProgress = true;
                Date currentDate = jobExecutionContext.getFireTime();
                log.debug("Executing Task: " + currentDate);
                Map<String, PetlJobConfig> jobs = etlService.getAllConfiguredJobs();
                log.debug("Found " + jobs.size() + " configured Jobs");
                for (String jobPath : jobs.keySet()) {
                    log.debug("Checking job: " + jobPath);
                    PetlJobConfig jobConfig = jobs.get(jobPath);
                    Schedule schedule = jobConfig.getSchedule();
                    boolean isScheduled = schedule != null && StringUtils.isNotBlank(schedule.getCron());
                    if (isScheduled) {
                        JobExecution latestExecution = etlService.getLatestJobExecution(jobPath);
                        if (latestExecution == null) {
                            log.info("Job: " + jobPath + " - Executing for the first time.");
                            etlService.executeJob(jobPath);
                        }
                        else {
                            Date lastStartDate = latestExecution.getStarted();
                            log.debug("Last Execution Start: " + lastStartDate);
                            Date lastEndDate = latestExecution.getCompleted();
                            log.debug("Last Execution End: " + lastEndDate);
                            if (lastEndDate != null) {
                                log.debug("Schedule found: " + schedule);
                                CronExpression cronExpression = new CronExpression(schedule.getCron());
                                Date nextScheduledDate = cronExpression.getNextValidTimeAfter(lastStartDate);
                                log.debug("Next scheduled for: " + nextScheduledDate);
                                if (nextScheduledDate != null && nextScheduledDate.compareTo(currentDate) <= 0) {
                                    log.info("Executing scheduled job: " + jobPath);
                                    log.info("Last run: " + lastStartDate);
                                    JobExecution execution = etlService.executeJob(jobPath);
                                    nextScheduledDate = cronExpression.getNextValidTimeAfter(execution.getStarted());
                                    log.info("Scheduled job will run again at: " + nextScheduledDate);
                                }
                                else {
                                    log.debug("This is in the future, not executing");
                                }
                            }
                            else {
                                log.debug("Latest execution is still in progress, not running again in parallel");
                            }
                        }
                    }
                    else {
                        log.debug("Job has no scheduled associated with it, not executing.");
                    }
                }
            }
            catch (Exception e) {
                log.error("An error occured while executing a scheduled job.  Aborting all remaining jobs.", e);
            }
            finally {
                inProgress = false;
            }
        }
        else {
            log.trace("Load Configurations Task already in progress, not running again in parallel");
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean enabled) {
        PetlScheduledExecutionTask.enabled = enabled;
    }
}
