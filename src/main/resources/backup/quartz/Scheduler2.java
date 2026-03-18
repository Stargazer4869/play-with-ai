package quartz;

import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.StdSchedulerFactory;

public class Scheduler2 {
    public static void main(String[] args) throws Exception {
        StdSchedulerFactory sf = new StdSchedulerFactory();

        sf.initialize("scheduler2.properties");

        Scheduler scheduler = sf.getScheduler();

// Scheduler will not execute jobs until it has been started (though they can be scheduled before start())
        scheduler.start();

//        JobDetail job = JobBuilder.newJob(ExceptionJob.class)
//                .withIdentity("job3", "group3")
//                .build();
//
//        // Trigger the job to run now, and then repeat every 40 seconds
//        Trigger trigger = TriggerBuilder.newTrigger()
//                .withIdentity("trigger3", "group3")
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
