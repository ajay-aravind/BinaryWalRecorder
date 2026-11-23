package in.ajay;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

public class WriteAheadLog implements IWriteAheadLog{
    private long lastWrittenSequence;
    private long lastDurableSequence;

    private static WriteAheadLog writeAheadLog;
    private final String filePath = AppConfig.get("wal.fileName");;
    FileInputStream inputStream;
    FileOutputStream outputStream;
    private final static byte[] MAGIC_BYTES = new byte[]{0x7A, 0x77, 0x3F, 0x29};
    private WalFlushMode flushMode;
    private final Object appendLock = new Object();
    private WalFileStatus walFileStatus;
    private WalStatus walStatus;
    FileChannel channel;

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

        this.walFileStatus = WalFileStatus.WRITABLE;
        this.walStatus = WalStatus.OPEN;

        try (FileInputStream in = new FileInputStream(filePath)) {
            inputStream = in;
            validateWalFile(false);
        } catch (IOException ex) {
            IO.println(
                    "Error while reading/writing to wal file. Error: "
                            + ex.getMessage()
            );
            throw ex;
        } finally {
            inputStream = null;
        }

        try (FileInputStream in = new FileInputStream(filePath)) {

            this.recoverStateInternal(in);
        } catch (IOException ex) {
            IO.println(
                    "Error while reading sequence number. Error: "
                            + ex.getMessage()
            );
            this.walFileStatus = WalFileStatus.CORRUPT;
            throw ex;
        }

        channel = FileChannel.open(
                path,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    public static synchronized WriteAheadLog getWriteAheadLog() throws IOException {
        if(writeAheadLog == null){
            writeAheadLog = new WriteAheadLog();
        }
        writeAheadLog.flushMode = WalFlushMode.AUTO;
        return writeAheadLog;
    }

    public static synchronized WriteAheadLog getWriteAheadLog(WalFlushMode flushMode) throws IOException {
        if(writeAheadLog == null){
            writeAheadLog = new WriteAheadLog();
        }
        writeAheadLog.flushMode = flushMode;
        return writeAheadLog;
    }

    @Override
    public long append(byte[] payload) throws IOException {
        synchronized (appendLock) {
            if(walFileStatus.equals(WalFileStatus.WRITABLE) && walStatus.equals(WalStatus.OPEN)) {
                BinaryRecord record = new BinaryRecord(payload, lastWrittenSequence + 1);
                ByteBuffer buffer = ByteBuffer.wrap(
                        record.getByteArrayForRecord()
                );

                Path path = Path.of(this.filePath);
                try
                {
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }

                    lastWrittenSequence++;

                    if (flushMode.equals(WalFlushMode.AUTO)) {
                        channel.force(false);
                        lastDurableSequence = lastWrittenSequence;
                    }
                } catch (Exception ex) {
                    IO.println("Error while appending to wal, marking wal unusable");
                    walFileStatus = WalFileStatus.CORRUPT;
                    channel.close();
                    throw new IOException("Error while appending to wal, marking wal unusable");
                }
                return record.getSequenceNumber();
            }else{
                throw new IOException("wal is unwritable, recover and/or closed. open/repair wal before trying to write it");
            }
        }
    }

    @Override
    public void flush() throws IOException {
        synchronized (appendLock) {
            if(walFileStatus.equals(WalFileStatus.WRITABLE) && walStatus.equals(WalStatus.OPEN)) {
                Path path = Path.of(this.filePath);
                try {
                    channel.force(false);
                    lastDurableSequence = lastWrittenSequence;
                } catch (Exception ex) {
                    IO.println("Error while flushing to wal, marking wal unusable");
                    channel.close();
                    walFileStatus = WalFileStatus.CORRUPT;
                    throw new IOException("Error while flushing to wal, marking wal unusable");
                }
            }else{
                throw new IOException("wal is unwritable and/or closed, repair and/or open wal before trying to write it");
            }
        }
    }


    @Override
    public void recover(IRecordConsumer consumer) throws IOException {

        if(walStatus.equals(WalStatus.OPEN)) {
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
                long previousSequenceNumber = 0;

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
                        break;
                    }

                    if (record == null) {
                        // Clean EOF between records.
                        break;
                    }

                    // Record is fully read. Update valid offset.
                    lastValidOffset = channel.position();
                    if(record.getSequenceNumber() != previousSequenceNumber+1){
                        throw new IOException("Invalid sequence number");
                    }
                    previousSequenceNumber++;
                    this.lastDurableSequence = record.getSequenceNumber();
                    this.lastWrittenSequence = record.getSequenceNumber();

                    consumer.consume(record);
                }

                IO.println("Done recovering records");

            } catch (FileNotFoundException ex) {
                IO.println("WAL file not found: " + ex.getMessage());
                throw ex;

            } catch (IOException ex) {
                this.walFileStatus = WalFileStatus.CORRUPT;
                IO.println(
                        "Error while recovering WAL: "
                                + ex.getMessage()
                );
                throw ex;
            }

            this.walFileStatus = WalFileStatus.WRITABLE;
            channel = FileChannel.open(
                    path,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);

        }else{
            throw new IOException("wal is closed");
        }
    }

    private void recoverStateInternal(InputStream inputStream) throws IOException {
        this.recover(record -> {});
    }

    @Override
    public long lastSequenceNumber() {
        return this.lastWrittenSequence;
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
        if(this.channel!=null && this.channel.isOpen()){
            channel.force(false);
            lastDurableSequence = lastWrittenSequence;
            channel.close();
        }
        this.walStatus = WalStatus.CLOSED;
    }

    private void validateWalFile(boolean expectsMagicBytesToBePresent) throws IOException {
        byte[] header = inputStream.readNBytes(4);

        if (header.length == 0) {
            if(expectsMagicBytesToBePresent) {
                throw new IOException("Invalid file: magic bytes not found");
            }
            else {
                var outputStream = new FileOutputStream(filePath, true);
                outputStream.write(MAGIC_BYTES);
                outputStream.flush();
                outputStream.close();
                return;
            }
        }

        if (header.length != 4 || !Arrays.equals(header, MAGIC_BYTES)) {
            throw new IOException("Invalid file: magic bytes mismatch");
        }
    }
}
