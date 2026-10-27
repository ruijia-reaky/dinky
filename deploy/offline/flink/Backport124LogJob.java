import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Backport124LogJob {
    public static class Source implements SourceFunction<Long> {
        private volatile boolean running = true;
        public void run(SourceContext<Long> context) throws Exception {
            Logger log = LoggerFactory.getLogger(Backport124LogJob.class);
            long sequence = 0;
            long deadline = System.currentTimeMillis() + 3600000L;
            log.info("BACKPORT_124_TEST_START duration_seconds=3600");
            while (running && System.currentTimeMillis() < deadline) {
                synchronized (context.getCheckpointLock()) { context.collect(sequence++); }
                if (sequence % 100 == 0) log.info("BACKPORT_124_LOG sequence={}", sequence);
                Thread.sleep(10L);
            }
            log.info("BACKPORT_124_TEST_END emitted={}", sequence);
        }
        public void cancel() { running = false; }
    }
    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        env.enableCheckpointing(15000L);
        env.addSource(new Source()).name("backport124-log-source").uid("backport124-log-source")
                .addSink(new SinkFunction<Long>() {}).name("discard-validation-records");
        env.execute("Dinky 1.2.4 application historical log validation");
    }
}
