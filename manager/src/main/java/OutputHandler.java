import com.amazonaws.services.sqs.model.Message;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public class OutputHandler implements Runnable {
    private String queueUrl;
    private Manager manager;
    private ExecutorService readersPool = Executors.newCachedThreadPool();
    private AtomicReference<String> output = new AtomicReference<String>();
    private int id;

    public OutputHandler(String queueUrl, Manager manager, int id) {
        this.queueUrl = queueUrl;
        this.manager = manager;
        this.id = id;
    }

    public void run() {
        //reads all from queue //TODO think how to verify all reviews was processed
        Message message = null;
        do {
            message = manager.readMessagesLookForFirstLine("PROCEEED", queueUrl);
            readersPool.execute(new OutputProcessor(queueUrl, manager, output, message));

        } while (message != null);

        //uploading
        manager.uploadOutputFile(manager.getBucketName(id),output.get(), id);
    }
}
