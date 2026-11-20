package in.ajay;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import static in.ajay.BinaryRecord.RecordLengthBytes;

public class WriteAheadLog implements IWriteAheadLog{
    private final AtomicLong sequenceNumber = new AtomicLong();
    private static WriteAheadLog writeAheadLog;
    private final String filePath = AppConfig.get("wal.fileName");;
    FileInputStream inputStream;
    FileOutputStream outputStream;
    private final static byte[] MAGIC_BYTES = new byte[]{0x7A, 0x77, 0x3F, 0x29};

    private WriteAheadLog() throws IOException {
        if(filePath.isBlank()) {
            throw new IllegalArgumentException("wal file name can't be empty");
        }

        Path path = Path.of(filePath);

        // Create parent directories if they don't exist
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }

        // Create file only if it doesn't exist
        if (Files.notExists(path)) {
            Files.createFile(path);
        }

        try (FileInputStream in = new FileInputStream(filePath)) {

            inputStream = in;
            outputStream = new FileOutputStream(filePath, true);

            try {
                validateWalFile(false);
            } catch (IOException | RuntimeException ex) {
                outputStream.close();
                outputStream = null;
                throw ex;
            }

        } catch (IOException ex) {
            IO.println(
                    "Error while reading/writing to wal file. Error: "
                            + ex.getMessage()
            );
            throw ex;
        } finally {
            inputStream = null;
        }
    }

    private void validateWalFile(boolean expectsMagicBytesToBePresent) throws IOException {
        byte[] header = inputStream.readNBytes(4);

        if (header.length == 0) {
            if(expectsMagicBytesToBePresent) {
                throw new IOException("Invalid file: magic bytes not found");
            }
            else {
                outputStream.write(MAGIC_BYTES);
                outputStream.flush();
                return;
            }
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
    public void recover(IRecordConsumer consumer) throws IOException {

        if(filePath.isBlank()) {
            throw new IllegalArgumentException("wal file name can't be empty");
        }

        try (FileInputStream recoveryStream = new FileInputStream(filePath)){

            // validateWalFile currently uses the inputStream field.
            // Temporarily point it to this stream.
            this.inputStream = recoveryStream;
            this.validateWalFile(true);

            while(true) {
                BinaryRecord record = BinaryRecord.getRecordFromByteArray(inputStream);
                if(record == null){
                    IO.println("Done recovering records, exiting");
                    break;
                }
                consumer.consume(record);
            }
        } catch (FileNotFoundException e) {
            IO.println("wal file not found. Error: "+e.getMessage());
            throw e;
        }
        catch(IOException ex){
            IO.println("Error while reading/writing to wal file. Error: "+ex.getMessage());
            throw ex;
        }

        finally {
            this.inputStream = null;
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
