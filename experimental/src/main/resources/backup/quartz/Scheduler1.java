package quartz;

import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.StdSchedulerFactory;

public class Scheduler1 {
    public static void main(String[] args) throws Exception {
        StdSchedulerFactory sf = new StdSchedulerFactory();

        sf.initialize("scheduler1.properties");

        Scheduler scheduler = sf.getScheduler();

        scheduler.start();

//        JobDetail job = JobBuilder.newJob(HiJob.class)
//                .withIdentity("job2", "group2")
//                .build();
//
//        // Trigger the job to run now, and then repeat every 40 seconds
//        Trigger trigger = TriggerBuilder.newTrigger()
//                .withIdentity("trigger2", "group2")
//                .startNow()
//                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
//                        .withIntervalInSeconds(4)
//                        .repeatForever())
//                .build();
//
//        // Tell quartz to schedule the job using our trigger
//        scheduler.scheduleJob(job, trigger);
    }
}
