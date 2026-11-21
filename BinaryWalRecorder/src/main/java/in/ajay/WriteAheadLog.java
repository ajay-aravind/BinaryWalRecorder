package in.ajay;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
                long lastSNo= this.getLastRecordSequenceNumber(in);
                this.sequenceNumber.getAndSet(lastSNo+1);
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
                inputStream.skipNBytes(4);
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

        if (filePath.isBlank()) {
            throw new IllegalArgumentException(
                    "wal file name can't be empty"
            );
        }

        Path path = Path.of(filePath);

        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {

            long fileSize = channel.size();

            // Validate magic header.
            if (fileSize < MAGIC_BYTES.length) {
                throw new IOException("Incomplete WAL header");
            }

            ByteBuffer header = ByteBuffer.allocate(MAGIC_BYTES.length);

            channel.position(0);

            while (header.hasRemaining()) {
                int bytesRead = channel.read(header);

                if (bytesRead == -1) {
                    throw new IOException("Incomplete WAL header");
                }
            }

            if (!Arrays.equals(header.array(), MAGIC_BYTES)) {
                throw new IOException("Invalid WAL magic bytes");
            }

            // First valid position is immediately after the header.
            long lastValidOffset = MAGIC_BYTES.length;

            InputStream recoveryStream = Channels.newInputStream(channel);

            while (channel.position() < fileSize) {

                BinaryRecord record;

                try {
                    record = BinaryRecord.getRecordFromByteArray(
                            recoveryStream
                    );

                } catch (EOFException ex) {

                    // A record started but was not fully written.
                    channel.truncate(lastValidOffset);
                    channel.force(true);

                    IO.println(
                            "Incomplete WAL tail detected. "
                                    + "Truncated to "
                                    + lastValidOffset
                                    + " bytes"
                    );

                    return;
                }

                if (record == null) {
                    // Clean EOF between records.
                    break;
                }

                // Record is fully read. Update valid offset.
                lastValidOffset = channel.position();

                // Restore sequence number here if needed.
                // sequenceNumber.set(...);

                consumer.consume(record);
            }

            IO.println("Done recovering records");

        } catch (FileNotFoundException ex) {
            IO.println("WAL file not found: " + ex.getMessage());
            throw ex;

        } catch (IOException ex) {
            IO.println(
                    "Error while recovering WAL: "
                            + ex.getMessage()
            );
            throw ex;
        }
    }

    private long getLastRecordSequenceNumber(InputStream inputStream) throws IOException {
        long sNo =-1L;
        try {
            while (true) {
                BinaryRecord record = BinaryRecord.getRecordFromByteArray(inputStream);
                if (record == null) {
                    IO.println("Done recovering records, exiting");
                    break;
                }
                sNo = record.getSequenceNumber();
            }
        }catch(Exception ex){
            IO.println("Exception while getting last record sequence number. Setting last sequence number as the last valid sequence number");
        }
        return sNo;
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
