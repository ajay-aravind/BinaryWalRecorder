package in.ajay;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.time.Instant;

public class AsyncFileFlusher {

    private final static int MAX_QUEUE_LENGTH = AppConfig.getInt("maxQueueLength");
    private final static int MAX_FLUSH_INTERVAL_IN_SECONDS = AppConfig.getInt("maxFlushIntervalInSeconds");
    int queueLength = 0;
    long lastFlushedEpoch = Instant.now().getEpochSecond();
    private final FileChannel fileChannel;

    public AsyncFileFlusher(FileChannel fileChannel) {
        this.fileChannel = fileChannel;
    }

    private boolean ShouldFlushToFile(){
        if(queueLength >= MAX_QUEUE_LENGTH || Instant.now().getEpochSecond()-lastFlushedEpoch >= MAX_FLUSH_INTERVAL_IN_SECONDS){
            return true;
        }
        return false;
    }

    private void flush() throws IOException {
        if(fileChannel!= null && fileChannel.isOpen()) {
            fileChannel.force(false);
        }
    }


}
