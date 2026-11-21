import in.ajay.AppConfig;
import in.ajay.BinaryRecord;
import in.ajay.WriteAheadLog;
import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WriteAheadLogTest {

    private static final byte[] MAGIC_BYTES =
            new byte[]{0x7A, 0x77, 0x3F, 0x29};

    private Path walPath;
    private WriteAheadLog wal;

    @BeforeEach
    void setUp() throws Exception {
        resetSingleton();

        walPath = Path.of(AppConfig.get("wal.fileName"));

        Files.createDirectories(walPath.getParent());
        Files.deleteIfExists(walPath);

        // Constructor expects an existing file.
        Files.createFile(walPath);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (wal != null) {
                wal.close();
            }
        } finally {
            resetSingleton();
            Files.deleteIfExists(walPath);
        }
    }


    private byte[] createRecordBytes(byte[] payload, long sequenceNumber) {
        return new BinaryRecord(payload, sequenceNumber)
                .getByteArrayForRecord();
    }

    private byte[] concatenate(byte[]... arrays) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        for (byte[] array : arrays) {
            output.write(array);
        }

        return output.toByteArray();
    }


    private void resetSingleton() throws Exception {
        Field field = WriteAheadLog.class.getDeclaredField("writeAheadLog");
        field.setAccessible(true);

        WriteAheadLog instance =
                (WriteAheadLog) field.get(null);

        if (instance != null) {
            try {
                instance.close();
            } catch (IOException ignored) {
                // Cleanup must continue.
            }
        }

        field.set(null, null);
        wal = null;
    }

    private WriteAheadLog getWal() throws IOException {
        if (wal == null) {
            wal = WriteAheadLog.getWriteAheadLog();
        }
        return wal;
    }

    private void appendRawBytes(byte[] bytes) throws IOException {
        Files.write(
                walPath,
                bytes,
                StandardOpenOption.APPEND
        );
    }

    private byte[] createRecord(byte[] payload, long sequenceNumber) {
        return new BinaryRecord(payload, sequenceNumber)
                .getByteArrayForRecord();
    }

    private List<byte[]> recoverAll() throws IOException {
        List<byte[]> recovered = new ArrayList<>();

        getWal().recover(record ->
                recovered.add(record.getByteArrayForRecord())
        );

        return recovered;
    }


    @Test
    void shouldRemoveIncompleteTailAndRecoverPreviousRecords()
            throws Exception {

        byte[] record1 = createRecordBytes(
                "first".getBytes(), 0
        );

        byte[] record2 = createRecordBytes(
                "second".getBytes(), 1
        );

        byte[] record3 = createRecordBytes(
                "third".getBytes(), 2
        );

        // Simulate a partial write of record 3.
        byte[] partialRecord3 = Arrays.copyOf(
                record3,
                record3.length - 2
        );

        byte[] walContents = concatenate(
                MAGIC_BYTES,
                record1,
                record2,
                partialRecord3
        );

        Files.write(walPath, walContents);

        long expectedValidSize =
                MAGIC_BYTES.length
                        + record1.length
                        + record2.length;

        WriteAheadLog instance = getWal();

        List<BinaryRecord> recoveredRecords = new ArrayList<>();

        instance.recover(recoveredRecords::add);

        // Only complete records should be recovered.
        assertEquals(2, recoveredRecords.size());

        // Incomplete tail must be removed.
        assertEquals(expectedValidSize, Files.size(walPath));

        // File must contain only header + valid records.
        byte[] expectedContents = concatenate(
                MAGIC_BYTES,
                record1,
                record2
        );

        assertArrayEquals(
                expectedContents,
                Files.readAllBytes(walPath)
        );
    }


    @Test
    void shouldRemoveTailContainingPartialRecordHeader()
            throws Exception {

        byte[] record1 = createRecordBytes(
                "first".getBytes(), 0
        );

        byte[] record2 = createRecordBytes(
                "second".getBytes(), 1
        );

        // Only the first 2 bytes of the next record.
        byte[] partialHeader = Arrays.copyOf(record2, 2);

        Files.write(
                walPath,
                concatenate(
                        MAGIC_BYTES,
                        record1,
                        partialHeader
                )
        );

        long expectedSize =
                MAGIC_BYTES.length + record1.length;

        WriteAheadLog instance = getWal();

        List<BinaryRecord> recovered = new ArrayList<>();

        instance.recover(recovered::add);

        assertEquals(1, recovered.size());
        assertEquals(expectedSize, Files.size(walPath));
    }


    @Test
    void shouldNotModifyWalWhenThereIsNoIncompleteTail()
            throws Exception {

        byte[] record1 = createRecordBytes(
                "first".getBytes(), 0
        );

        byte[] record2 = createRecordBytes(
                "second".getBytes(), 1
        );

        byte[] originalContents = concatenate(
                MAGIC_BYTES,
                record1,
                record2
        );

        Files.write(walPath, originalContents);

        WriteAheadLog instance = getWal();

        List<BinaryRecord> recovered = new ArrayList<>();

        instance.recover(recovered::add);

        assertEquals(2, recovered.size());

        assertArrayEquals(
                originalContents,
                Files.readAllBytes(walPath)
        );
    }



    // -------------------------------------------------------
    // File initialization tests
    // -------------------------------------------------------

    @Test
    void shouldInitializeEmptyWalFileWithMagicBytes() throws Exception {
        getWal();

        byte[] fileContents = Files.readAllBytes(walPath);

        assertArrayEquals(MAGIC_BYTES, fileContents);
    }

    @Test
    void shouldNotOverwriteExistingWalFileHeader() throws Exception {
        Files.write(walPath, MAGIC_BYTES);

        getWal();

        assertArrayEquals(
                MAGIC_BYTES,
                Files.readAllBytes(walPath)
        );
    }

    @Test
    void shouldRejectInvalidMagicBytes() {
        assertDoesNotThrow(() -> Files.write(
                walPath,
                new byte[]{0x01, 0x02, 0x03, 0x04}
        ));

        assertThrows(
                IOException.class,
                () -> WriteAheadLog.getWriteAheadLog()
        );
    }

    @Test
    void shouldRejectIncompleteMagicHeader() throws Exception {
        Files.write(
                walPath,
                new byte[]{0x7A, 0x77}
        );

        assertThrows(
                IOException.class,
                () -> WriteAheadLog.getWriteAheadLog()
        );
    }

    // -------------------------------------------------------
    // Append tests
    // -------------------------------------------------------

    @Test
    void shouldAppendSingleRecord() throws Exception {
        byte[] payload = "hello".getBytes();

        long sequence = getWal().append(payload);

        assertEquals(1, sequence);

        assertTrue(Files.size(walPath) > MAGIC_BYTES.length);
    }

    @Test
    void shouldAppendMultipleRecords() throws Exception {
        WriteAheadLog instance = getWal();

        long first = instance.append("first".getBytes());
        long second = instance.append("second".getBytes());
        long third = instance.append("third".getBytes());

        assertEquals(1, first);
        assertEquals(2, second);
        assertEquals(3, third);

        assertEquals(3, instance.lastSequenceNumber());
    }

    @Test
    void shouldAppendEmptyPayload() throws Exception {
        assertDoesNotThrow(
                () -> getWal().append(new byte[0])
        );
    }

    @Test
    void shouldIncreaseWalFileSizeAfterAppending() throws Exception {
        getWal();

        long initialSize = Files.size(walPath);

        getWal().append("hello".getBytes());

        long finalSize = Files.size(walPath);

        assertTrue(finalSize > initialSize);
    }

    @Test
    void shouldKeepSequenceNumbersMonotonicallyIncreasing()
            throws Exception {

        WriteAheadLog instance = getWal();

        for (int i = 1; i <= 100; i++) {
            long sequence = instance.append(
                    ("message-" + i).getBytes()
            );

            assertEquals(i, sequence);
        }

        assertEquals(100, instance.lastSequenceNumber());
    }

    // -------------------------------------------------------
    // Recovery tests
    // -------------------------------------------------------

    @Test
    void shouldRecoverEmptyWal() throws Exception {
        List<byte[]> recovered = recoverAll();

        assertTrue(recovered.isEmpty());
    }

    @Test
    void shouldRecoverSingleRecord() throws Exception {
        byte[] payload = "hello".getBytes();

        getWal().append(payload);

        List<byte[]> recovered = recoverAll();

        assertEquals(1, recovered.size());

        assertArrayEquals(
                createRecord(payload, 0),
                recovered.get(0)
        );
    }

    @Test
    void shouldRecoverMultipleRecordsInOrder() throws Exception {
        WriteAheadLog instance = getWal();

        List<byte[]> expected = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            byte[] payload = ("message-" + i).getBytes();

            instance.append(payload);

            expected.add(createRecord(payload, i));
        }

        List<byte[]> recovered = recoverAll();

        assertEquals(expected.size(), recovered.size());

        for (int i = 0; i < expected.size(); i++) {
            assertArrayEquals(expected.get(i), recovered.get(i));
        }
    }

    @Test
    void shouldRecoverRecordsOnlyOncePerRecoveryInvocation()
            throws Exception {

        getWal().append("first".getBytes());
        getWal().append("second".getBytes());

        List<byte[]> firstRecovery = recoverAll();
        List<byte[]> secondRecovery = recoverAll();

        assertEquals(2, firstRecovery.size());
        assertEquals(2, secondRecovery.size());
    }

    // -------------------------------------------------------
    // Corruption and partial-write tests
    // -------------------------------------------------------

    @Test
    void shouldRejectWalWithIncompleteMagicBytesDuringRecovery()
            throws Exception {

        Files.write(
                walPath,
                new byte[]{0x7A, 0x77}
        );

        // The constructor itself validates the header.
        assertThrows(
                IOException.class,
                () -> WriteAheadLog.getWriteAheadLog()
        );
    }

    @Test
    void shouldRejectTruncatedRecord() throws Exception {
        byte[] validRecord =
                createRecord("hello".getBytes(), 0);

        byte[] partialRecord = Arrays.copyOf(
                validRecord,
                validRecord.length - 1
        );

        appendRawBytes(MAGIC_BYTES);
        appendRawBytes(partialRecord);
        appendRawBytes(validRecord);

        assertThrows(
                IOException.class,
                () -> recoverAll()
        );
    }

    @Test
    void shouldRejectRecordWithInvalidLength() throws Exception {
        byte[] malformedRecord = createRecord(
                "hello".getBytes(),
                0
        );

        // Corrupt the first byte of the record.
        malformedRecord[0] ^= (byte) 0xFF;

        appendRawBytes(MAGIC_BYTES);
        appendRawBytes(malformedRecord);

        assertThrows(
                IllegalArgumentException.class,
                () -> recoverAll()
        );
    }

    // -------------------------------------------------------
    // Lifecycle tests
    // -------------------------------------------------------

    @Test
    void shouldFlushWithoutThrowingException() throws Exception {
        getWal().append("hello".getBytes());

        assertDoesNotThrow(() -> getWal().flush());
    }

    @Test
    void shouldCloseWithoutThrowingException() throws Exception {
        getWal().append("hello".getBytes());

        assertDoesNotThrow(() -> getWal().close());

        wal = null;
        resetSingleton();
    }

    @Test
    void shouldNotRestartSequenceNumberFromZeroAfterRecovery()
            throws Exception {

        // First WAL instance: write three records
        WriteAheadLog firstInstance = getWal();

        firstInstance.append("first".getBytes());
        firstInstance.append("second".getBytes());
        firstInstance.append("third".getBytes());

        assertEquals(3, firstInstance.lastSequenceNumber());

        // Simulate process shutdown
        firstInstance.close();
        wal = null;
        resetSingleton();

        // Simulate application restart
        WriteAheadLog restartedInstance = getWal();

        // Recover previously persisted records
        restartedInstance.recover(record -> {
            // Replay records (no-op for this test)
        });

        // Sequence number should be restored from recovered records
        assertEquals(3, restartedInstance.lastSequenceNumber());

        // Next record should continue from sequence number 3
        long sequence = restartedInstance.append("fourth".getBytes());

        assertEquals(4, sequence);
        assertEquals(4, restartedInstance.lastSequenceNumber());
    }

}
