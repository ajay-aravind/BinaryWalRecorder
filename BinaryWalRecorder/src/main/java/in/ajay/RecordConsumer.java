package in.ajay;

import java.nio.charset.StandardCharsets;

public class RecordConsumer {

    void Consume(BinaryRecord record){
        IO.println(new String(record.getPayload(), StandardCharsets.UTF_8));
        IO.println(record.getSequenceNumber());
    }
}
