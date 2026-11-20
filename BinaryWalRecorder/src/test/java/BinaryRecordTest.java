
package in.ajay;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

import static org.junit.jupiter.api.Assertions.*;

class BinaryRecordTest {

    private static final int RECORD_LENGTH_OFFSET = 0;
    private static final int VERSION_OFFSET = 4;
    private static final int SEQUENCE_OFFSET = 5;
    private static final int PAYLOAD_LENGTH_OFFSET = 13;
    private static final int PAYLOAD_OFFSET = 17;

    private static final int HEADER_SIZE = 17;
    private static final int CHECKSUM_SIZE = 8;
    private static final int MIN_PAYLOAD_SIZE = 3;
    private static final int MAX_PAYLOAD_SIZE = 1024 * 1024;

    private static final byte VALID_VERSION = 1;

    // ------------------------------------------------
    // Test helpers
    // ------------------------------------------------

    private BinaryRecord deserialize(byte[] bytes) throws IOException {
        return BinaryRecord.getRecordFromByteArray(
                new ByteArrayInputStream(bytes)
        );
    }

    /*
     * Construct a valid record independently of BinaryRecord.
     *
     * The checksum calculation mirrors the current implementation:
     * CRC32 over the complete record with the last 8 bytes zeroed.
     */
    private byte[] createValidRecord(
            byte[] payload,
            long sequenceNumber,
            byte version) {

        int recordLength = HEADER_SIZE + payload.length + CHECKSUM_SIZE;

        ByteBuffer buffer = ByteBuffer.allocate(recordLength)
                .order(ByteOrder.BIG_ENDIAN);

        buffer.putInt(recordLength);
        buffer.put(version);
        buffer.putLong(sequenceNumber);
        buffer.putInt(payload.length);
        buffer.put(payload);

        long checksum = BinaryRecord.getCRC32Checksum(buffer.array());

        buffer.putLong(checksum);

        return buffer.array();
    }

    private byte[] createValidRecord(byte[] payload, long sequenceNumber) {
        return createValidRecord(payload, sequenceNumber, VALID_VERSION);
    }

    private long calculateChecksum(byte[] bytes) {
        Checksum checksum = new CRC32();
        checksum.update(bytes, 0, bytes.length);
        return checksum.getValue();
    }

    private void updateInt(byte[] bytes, int offset, int value) {
        ByteBuffer.wrap(bytes)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(offset, value);
    }

    private void updateLong(byte[] bytes, int offset, long value) {
        ByteBuffer.wrap(bytes)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(offset, value);
    }

    private void assertInvalidRecord(byte[] bytes) {
        assertThrows(Exception.class, () -> deserialize(bytes));
    }

    private byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------
    // 1. Positive tests
    // ------------------------------------------------

    @Test
    void shouldDeserializeValidRecord() throws IOException {

        byte[] payload = utf8("Hello WAL");

        byte[] bytes = createValidRecord(payload, 100L);

        BinaryRecord record = deserialize(bytes);

        assertNotNull(record);
        assertEquals(100L, record.getSequenceNumber());
        assertArrayEquals(payload, record.getPayload());
    }

    @Test
    void shouldSerializeAndDeserializeValidRecord() throws IOException {

        byte[] payload = utf8("Hello WAL");

        BinaryRecord original = new BinaryRecord(payload, 100L);

        byte[] bytes = original.getByteArrayForRecord();

        BinaryRecord restored = deserialize(bytes);

        assertEquals(original.getSequenceNumber(),
                restored.getSequenceNumber());

        assertArrayEquals(original.getPayload(), restored.getPayload());
    }

    @Test
    void shouldHandleMinimumAllowedPayload() throws IOException {

        byte[] payload = {1, 2, 3};

        BinaryRecord record = deserialize(createValidRecord(payload, 1L));

        assertArrayEquals(payload, record.getPayload());
    }

    @Test
    void shouldHandleMaximumAllowedPayload() throws IOException {

        byte[] payload = new byte[MAX_PAYLOAD_SIZE];

        new Random(42).nextBytes(payload);

        BinaryRecord record = deserialize(createValidRecord(payload, 1L));

        assertArrayEquals(payload, record.getPayload());
    }

    @Test
    void shouldHandleUnicodePayload() throws IOException {

        String message = "Hello 世界 🌍 नमस्ते";

        byte[] payload = utf8(message);

        BinaryRecord record = deserialize(createValidRecord(payload, 10L));

        assertEquals(message,
                new String(record.getPayload(), StandardCharsets.UTF_8));
    }

    @Test
    void shouldHandleLargeSequenceNumber() throws IOException {

        BinaryRecord record = deserialize(
                createValidRecord(utf8("test"), Long.MAX_VALUE)
        );

        assertEquals(Long.MAX_VALUE, record.getSequenceNumber());
    }

    @Test
    void shouldHandleNegativeSequenceNumber() throws IOException {

        BinaryRecord record = deserialize(
                createValidRecord(utf8("test"), Long.MIN_VALUE)
        );

        assertEquals(Long.MIN_VALUE, record.getSequenceNumber());
    }

    @Test
    void shouldHandleZeroSequenceNumber() throws IOException {

        BinaryRecord record = deserialize(
                createValidRecord(utf8("test"), 0L)
        );

        assertEquals(0L, record.getSequenceNumber());
    }

    // ------------------------------------------------
    // 2. Serialization format tests
    // ------------------------------------------------

    @Test
    void shouldGenerateCorrectRecordLength() {

        byte[] payload = utf8("test");

        byte[] bytes = new BinaryRecord(payload, 1L)
                .getByteArrayForRecord();

        int expectedLength = HEADER_SIZE + payload.length + CHECKSUM_SIZE;

        assertEquals(expectedLength, bytes.length);

        int actualLength = ByteBuffer.wrap(bytes)
                .order(ByteOrder.BIG_ENDIAN)
                .getInt();

        assertEquals(expectedLength, actualLength);
    }

    @Test
    void shouldWriteSequenceNumberAtCorrectOffset() {

        byte[] bytes = new BinaryRecord(utf8("test"), 12345L)
                .getByteArrayForRecord();

        long sequence = ByteBuffer.wrap(bytes)
                .order(ByteOrder.BIG_ENDIAN)
                .getLong(SEQUENCE_OFFSET);

        assertEquals(12345L, sequence);
    }

    @Test
    void shouldWritePayloadLengthAtCorrectOffset() {

        byte[] payload = utf8("Hello");

        byte[] bytes = new BinaryRecord(payload, 1L)
                .getByteArrayForRecord();

        int payloadLength = ByteBuffer.wrap(bytes)
                .order(ByteOrder.BIG_ENDIAN)
                .getInt(PAYLOAD_LENGTH_OFFSET);

        assertEquals(payload.length, payloadLength);
    }

    @Test
    void shouldWriteCorrectChecksum() {

        byte[] bytes = new BinaryRecord(utf8("Hello WAL"), 1L)
                .getByteArrayForRecord();

        byte[] checksumInput = Arrays.copyOf(bytes, bytes.length);

        // Serializer calculates checksum before writing checksum itself.
        Arrays.fill(checksumInput,
                bytes.length - CHECKSUM_SIZE,
                bytes.length,
                (byte) 0);

        long expectedChecksum = calculateChecksum(checksumInput);

        long actualChecksum = ByteBuffer.wrap(bytes)
                .order(ByteOrder.BIG_ENDIAN)
                .getLong(bytes.length - CHECKSUM_SIZE);

        assertEquals(expectedChecksum, actualChecksum);
    }

    // ------------------------------------------------
    // 3. EOF and stream behavior
    // ------------------------------------------------

    @Test
    void shouldReturnNullWhenStreamIsEmpty() throws IOException {

        BinaryRecord record = deserialize(new byte[0]);

        assertNull(record);
    }

    @Test
    void shouldReturnNullWhenStreamReachesEOF() throws IOException {

        byte[] bytes = createValidRecord(utf8("test"), 1L);

        ByteArrayInputStream input = new ByteArrayInputStream(bytes);

        BinaryRecord first = BinaryRecord.getRecordFromByteArray(input);

        assertNotNull(first);

        BinaryRecord second = BinaryRecord.getRecordFromByteArray(input);

        assertNull(second);
    }

    @Test
    void shouldReadMultipleRecordsFromSameStream() throws IOException {

        byte[] first = createValidRecord(utf8("first"), 1L);
        byte[] second = createValidRecord(utf8("second"), 2L);
        byte[] third = createValidRecord(utf8("third"), 3L);

        ByteArrayOutputStream output = new ByteArrayOutputStream();

        output.write(first);
        output.write(second);
        output.write(third);

        ByteArrayInputStream input =
                new ByteArrayInputStream(output.toByteArray());

        BinaryRecord record1 = BinaryRecord.getRecordFromByteArray(input);
        BinaryRecord record2 = BinaryRecord.getRecordFromByteArray(input);
        BinaryRecord record3 = BinaryRecord.getRecordFromByteArray(input);
        BinaryRecord record4 = BinaryRecord.getRecordFromByteArray(input);

        assertEquals(1L, record1.getSequenceNumber());
        assertEquals(2L, record2.getSequenceNumber());
        assertEquals(3L, record3.getSequenceNumber());
        assertNull(record4);
    }

    // ------------------------------------------------
    // 4. Partial record / truncated input tests
    // ------------------------------------------------

    @Test
    void shouldRejectPartialLengthPrefix() {

        for (int length = 1; length <= 3; length++) {

            byte[] partial = new byte[length];

            assertThrows(IOException.class, () -> deserialize(partial));
        }
    }

    @Test
    void shouldRejectTruncatedRecordBody() {

        byte[] valid = createValidRecord(utf8("Hello WAL"), 1L);

        for (int length = 4; length < valid.length; length++) {

            byte[] truncated = Arrays.copyOf(valid, length);

            assertThrows(IOException.class,
                    () -> deserialize(truncated),
                    "Should reject truncated record of length " + length);
        }
    }

    @Test
    void shouldRejectRecordMissingOnlyLastByte() {

        byte[] valid = createValidRecord(utf8("Hello"), 1L);

        byte[] truncated = Arrays.copyOf(valid, valid.length - 1);

        assertThrows(IOException.class, () -> deserialize(truncated));
    }

    @Test
    void shouldRejectRecordMissingEntireChecksum() {

        byte[] valid = createValidRecord(utf8("Hello"), 1L);

        byte[] truncated = Arrays.copyOf(
                valid,
                valid.length - CHECKSUM_SIZE
        );

        assertThrows(IOException.class, () -> deserialize(truncated));
    }

    @Test
    void shouldRejectRecordWithPartialPayload() {

        byte[] valid = createValidRecord(utf8("Hello WAL"), 1L);

        byte[] truncated = Arrays.copyOf(valid, PAYLOAD_OFFSET + 3);

        assertThrows(IOException.class, () -> deserialize(truncated));
    }

    // ------------------------------------------------
    // 5. Invalid record version tests
    // ------------------------------------------------

    @Test
    void shouldRejectUnsupportedVersion() {

        byte[] bytes = createValidRecord(
                utf8("test"),
                1L,
                (byte) 2
        );

        assertThrows(IllegalArgumentException.class,
                () -> deserialize(bytes));
    }

    @Test
    void shouldRejectZeroVersion() {

        byte[] bytes = createValidRecord(
                utf8("test"),
                1L,
                (byte) 0
        );

        assertThrows(IllegalArgumentException.class,
                () -> deserialize(bytes));
    }

    @Test
    void shouldRejectNegativeVersion() {

        byte[] bytes = createValidRecord(
                utf8("test"),
                1L,
                (byte) -1
        );

        assertThrows(IllegalArgumentException.class,
                () -> deserialize(bytes));
    }

    // ------------------------------------------------
    // 6. Invalid payload length tests
    // ------------------------------------------------

    @Test
    void shouldRejectNegativePayloadLength() {

        byte[] bytes = createValidRecord(utf8("test"), 1L);

        updateInt(bytes, PAYLOAD_LENGTH_OFFSET, -1);

        assertThrows(IllegalArgumentException.class,
                () -> deserialize(bytes));
    }

    @Test
    void shouldRejectZeroPayloadLength() {

        byte[] bytes = createValidRecord(utf8("test"), 1L);

        updateInt(bytes, PAYLOAD_LENGTH_OFFSET, 0);

        assertThrows(IllegalArgumentException.class,
                () -> deserialize(bytes));
    }

    @Test
    void shouldRejectPayloadSmallerThanMinimum() {

        byte[] bytes = createValidRecord(utf8("test"), 1L);

        updateInt(bytes, PAYLOAD_LENGTH_OFFSET, 2);

        assertThrows(IllegalArgumentException.class,
                () -> deserialize(bytes));
    }

    @Test
    void shouldRejectPayloadLargerThanMaximum() {

        byte[] bytes = createValidRecord(utf8("test"), 1L);

        updateInt(bytes, PAYLOAD_LENGTH_OFFSET, MAX_PAYLOAD_SIZE + 1);

        assertThrows(IllegalArgumentException.class,
                () -> deserialize(bytes));
    }

    // ------------------------------------------------
    // 7. Record length validation tests
    // ------------------------------------------------

    @Test
    void shouldRejectNegativeRecordLength() {

        byte[] bytes = createValidRecord(utf8("test"), 1L);

        updateInt(bytes, RECORD_LENGTH_OFFSET, -1);

        assertInvalidRecord(bytes);
    }

    @Test
    void shouldRejectZeroRecordLength() {

        byte[] bytes = createValidRecord(utf8("test"), 1L);

        updateInt(bytes, RECORD_LENGTH_OFFSET, 0);

        assertInvalidRecord(bytes);
    }

    @Test
    void shouldRejectRecordLengthSmallerThanHeader() {

        byte[] bytes = createValidRecord(utf8("test"), 1L);

        updateInt(bytes, RECORD_LENGTH_OFFSET, HEADER_SIZE - 1);

        assertInvalidRecord(bytes);
    }

    @Test
    void shouldRejectRecordLengthSmallerThanActualRecord() {

        byte[] bytes = createValidRecord(utf8("test payload"), 1L);

        updateInt(bytes, RECORD_LENGTH_OFFSET, bytes.length - 1);

        assertInvalidRecord(bytes);
    }

    @Test
    void shouldRejectRecordLengthLargerThanAvailableInput() {

        byte[] bytes = createValidRecord(utf8("test"), 1L);

        updateInt(bytes, RECORD_LENGTH_OFFSET, bytes.length + 100);

        assertThrows(IOException.class, () -> deserialize(bytes));
    }

    @Test
    void shouldRejectExcessivelyLargeRecordLengthWithoutAllocatingIt() {

        byte[] prefix = ByteBuffer.allocate(4)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(Integer.MAX_VALUE)
                .array();

        assertThrows(Exception.class, () -> deserialize(prefix));
    }

    // ------------------------------------------------
    // 8. Checksum and corruption tests
    // ------------------------------------------------

    @Test
    void shouldRejectModifiedPayload() {

        byte[] bytes = createValidRecord(utf8("original"), 1L);

        bytes[PAYLOAD_OFFSET] ^= 0x01;

        assertInvalidRecord(bytes);
    }

    @Test
    void shouldRejectModifiedChecksum() {

        byte[] bytes = createValidRecord(utf8("original"), 1L);

        bytes[bytes.length - 1] ^= 0x01;

        assertInvalidRecord(bytes);
    }

    @Test
    void shouldRejectModifiedSequenceNumber() {

        byte[] bytes = createValidRecord(utf8("original"), 1L);

        bytes[SEQUENCE_OFFSET] ^= 0x01;

        assertInvalidRecord(bytes);
    }

    @Test
    void shouldRejectModifiedPayloadLength() {

        byte[] bytes = createValidRecord(utf8("original"), 1L);

        updateInt(bytes, PAYLOAD_LENGTH_OFFSET, 100);

        assertInvalidRecord(bytes);
    }

    // ------------------------------------------------
    // 9. Unexpected and malformed inputs
    // ------------------------------------------------

    @Test
    void shouldRejectRandomGarbage() {

        byte[] garbage = new byte[100];

        new Random(42).nextBytes(garbage);

        // Use a bounded record length to avoid a huge allocation.
        updateInt(garbage, RECORD_LENGTH_OFFSET, garbage.length);

        assertInvalidRecord(garbage);
    }

    @Test
    void shouldRejectAllZeroBytes() {

        byte[] garbage = new byte[100];

        assertInvalidRecord(garbage);
    }

    @Test
    void shouldRejectRecordWithExtraTrailingData() throws IOException {

        byte[] valid = createValidRecord(utf8("test"), 1L);

        byte[] malformed = Arrays.copyOf(valid, valid.length + 3);

        malformed[valid.length] = 1;
        malformed[valid.length + 1] = 2;
        malformed[valid.length + 2] = 3;

        ByteArrayInputStream input =
                new ByteArrayInputStream(malformed);

        BinaryRecord first = BinaryRecord.getRecordFromByteArray(input);

        assertNotNull(first);

        // Trailing garbage becomes a partial length prefix.
        assertThrows(IOException.class,
                () -> BinaryRecord.getRecordFromByteArray(input));
    }
}
