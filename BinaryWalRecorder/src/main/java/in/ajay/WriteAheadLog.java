package in.ajay;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import static in.ajay.BinaryRecord.RecordLengthBytes;

public class WriteAheadLog implements IWriteAheadLog{
    private final AtomicLong sequenceNumber = new AtomicLong();
    private static WriteAheadLog writeAheadLog;
    FileInputStream inputStream;
    FileOutputStream outputStream;
    private final static byte[] MAGIC_BYTES = new byte[]{0x7A, 0x77, 0x3F, 0x29};

    private WriteAheadLog() throws IOException {
        String fileName = AppConfig.get("wal.fileName");
        if(fileName.isBlank()) {
            throw new IllegalArgumentException("wal file name can't be empty");
        }

        try {
            inputStream = new FileInputStream(fileName);
            outputStream = new FileOutputStream(fileName, true);
            this.validateWalFile();
        } catch (FileNotFoundException e) {
            IO.println("wal file not found. Error: "+e.getMessage());
            throw e;
        }
        catch(IOException ex){
            IO.println("Error while reading/writing to wal file. Error: "+ex.getMessage());
            throw ex;
        }
    }

    private void validateWalFile() throws IOException {
        byte[] header = inputStream.readNBytes(4);

        if (header.length == 0) {
            outputStream.write(MAGIC_BYTES);
            outputStream.flush();
            return;
        }

        if (header.length != 4 || !Arrays.equals(header, MAGIC_BYTES)) {
            throw new IOException("Invalid file: magic bytes mismatch");
        }
    }

    public static synchronized WriteAheadLog getWriteAheadLog() throws IOException {
        if(writeAheadLog == null){
            writeAheadLog = new WriteAheadLog();
        }
        return writeAheadLog;
    }

    @Override
    public synchronized long append(byte[] payload) throws IOException {
        BinaryRecord record = new BinaryRecord(payload, sequenceNumber.getAndIncrement());
        byte[] binaryRecordBytes = record.getByteArrayForRecord();
        outputStream.write(binaryRecordBytes);
        outputStream.flush();
        return sequenceNumber.get();
    }

    @Override
    public void flush() throws IOException {
        this.outputStream.flush();
    }

    @Override
    public void recover(RecordConsumer consumer) throws IOException {
        String fileName = AppConfig.get("wal.fileName");
        if(fileName.isBlank()) {
            throw new IllegalArgumentException("wal file name can't be empty");
        }

        try {
            inputStream = new FileInputStream(fileName);
            outputStream = new FileOutputStream(fileName, true);
            this.validateWalFile();
        } catch (FileNotFoundException e) {
            IO.println("wal file not found. Error: "+e.getMessage());
            throw e;
        }
        catch(IOException ex){
            IO.println("Error while reading/writing to wal file. Error: "+ex.getMessage());
            throw ex;
        }

        while(true) {


            BinaryRecord record = BinaryRecord.getRecordFromByteArray(inputStream);
            consumer.Consume(record);
        }
    }

    @Override
    public long lastSequenceNumber() {
        return this.sequenceNumber.get();
    }

    @Override
    public void close() throws IOException {
        if(inputStream != null){
            inputStream.close();
        }

        if(outputStream!=null){
            outputStream.flush();
            outputStream.close();
        }
    }
}
