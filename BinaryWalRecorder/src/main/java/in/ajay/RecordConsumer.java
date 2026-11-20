package in.ajay;

import java.nio.charset.StandardCharsets;

public class RecordConsumer implements IRecordConsumer{

    InMemoryDb db;

    public RecordConsumer(InMemoryDb db) {
        this.db = db;
    }

    public void consume(BinaryRecord record){
        IO.println(new String(record.getPayload(), StandardCharsets.UTF_8));
        IO.println(record.getSequenceNumber());

        String recordString = new String(record.getPayload(), StandardCharsets.UTF_8);
        var recordTokens = recordString.split(";",2);
        db.put(recordTokens[0], recordTokens[1]);
    }
}
