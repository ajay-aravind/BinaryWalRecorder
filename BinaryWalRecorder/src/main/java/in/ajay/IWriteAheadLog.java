package in.ajay;

import java.io.IOException;

public interface IWriteAheadLog extends AutoCloseable{
    long append(byte[] payload) throws IOException; // This can either just append or append and flush based on settings

    void flush() throws IOException; // if we append in batches, we can flush once. Only after a batch of appens are flushed, the db will say the transaction is successul

    void recover(RecordConsumer consumer) throws IOException;

    long lastSequenceNumber();

    @Override
    void close() throws IOException;
}
