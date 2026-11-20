package in.ajay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

public class MoonLightDb {
    InMemoryDb dbEngine;
    IRecordConsumer recordConsumer;
    IWriteAheadLog writeAheadLog;

    public MoonLightDb(InMemoryDb dbEngine, IRecordConsumer recordConsumer, IWriteAheadLog writeAheadLog) {
        this.dbEngine = dbEngine;
        this.recordConsumer = recordConsumer;
        this.writeAheadLog = writeAheadLog;
    }

    public MoonLightDb() {
        this.dbEngine = new InMemoryDb();
        this.recordConsumer = new RecordConsumer(this.dbEngine);
        try {
            this.writeAheadLog = WriteAheadLog.getWriteAheadLog();
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    public void start(){
        RecordConsumer consumer = new RecordConsumer(dbEngine);
        try {
            IWriteAheadLog wal = WriteAheadLog.getWriteAheadLog();
            wal.recover(consumer);
        }catch(IOException ex){
            IO.println("Error occurred while recovering the data:"+ ex.getMessage());
        }
    }

    public void put(String key, String value){
        dbEngine.put(key, value);
        try {
            writeAheadLog.append((key+";"+value).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            IO.println("Failed to write to the wal");
        }
    }

    public void clearDataSoFar(){
        dbEngine.deleteAll();
    }

    public ConcurrentHashMap<String, String> getAllData(){
        return dbEngine.getAllData();
    }
}
