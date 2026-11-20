
import in.ajay.BinaryRecord;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class BinaryRecordTest {

    private static final int RECORD_LENGTH_OFFSET = 0;
    private static final int VERSION_OFFSET = 4;
    private static final int SEQUENCE_OFFSET = 5;
    private static final int PAYLOAD_LENGTH_OFFSET = 13;
    private static final int PAYLOAD_OFFSET = 17;
    private static final int CHECKSUM_SIZE = 8;

    private byte[] serialize(byte[] payload, long sequence) {
        return new BinaryRecord(payload, sequence)
                .getByteArrayForRecord();
    }

    /*
     * The current deserializer expects the buffer to start
     * immediately after the 4-byte record length.
     */
    private BinaryRecord deserialize(byte[] bytes) {
        return BinaryRecord.getRecordFromByteArray(
                ByteBuffer.wrap(bytes, 4, bytes.length - 4)
        );
    }

    private void assertInvalid(byte[] bytes) {
        assertThrows(RuntimeException.class, () -> deserialize(bytes));
    }

    // ------------------------------------------------
    // Positive tests
    // ------------------------------------------------

    @Test
    void shouldSerializeAndDeserializeValidRecord() {

        byte[] payload = "Hello WAL".getBytes(StandardCharsets.UTF_8);

        BinaryRecord original = new BinaryRecord(payload, 100L);

        byte[] bytes = original.getByteArrayForRecord();

        BinaryRecord restored = deserialize(bytes);

        assertEquals(100L, restored.getSequenceNumber());
        assertArrayEquals(payload, restored.getPayload());
    }

    @Test
    void shouldHandleEmptyPayload() {

        byte[] payload = new byte[0];

        byte[] bytes = serialize(payload, 1L);

        BinaryRecord restored = deserialize(bytes);

        assertEquals(1L, restored.getSequenceNumber());
        assertArrayEquals(payload, restored.getPayload());
    }

    @Test
    void shouldHandleLargePayload() {

        byte[] payload = new byte[1024 * 1024];

        new Random(42).nextBytes(payload);

        byte[] bytes = serialize(payload, 123L);

        BinaryRecord restored = deserialize(bytes);

        assertEquals(123L, restored.getSequenceNumber());
        assertArrayEquals(payload, restored.getPayload());
    }

    @Test
    void shouldHandleUnicodePayload() {

        String message = "Hello 世界 🌍 नमस्ते";

        byte[] payload = message.getBytes(StandardCharsets.UTF_8);

        byte[] bytes = serialize(payload, 10L);

        BinaryRecord restored = deserialize(bytes);

        assertEquals(message,
                new String(restored.getPayload(), StandardCharsets.UTF_8));
    }

    @Test
    void shouldHandleMaximumSequenceNumber() {

        byte[] bytes = serialize(
                "test".getBytes(StandardCharsets.UTF_8),
                Long.MAX_VALUE
        );

        BinaryRecord restored = deserialize(bytes);

        assertEquals(Long.MAX_VALUE, restored.getSequenceNumber());
    }

    @Test
    void shouldHandleNegativeSequenceNumber() {

        byte[] bytes = serialize(
                "test".getBytes(StandardCharsets.UTF_8),
                Long.MIN_VALUE
        );

        BinaryRecord restored = deserialize(bytes);

        assertEquals(Long.MIN_VALUE, restored.getSequenceNumber());
    }

    @Test
    void shouldGenerateExpectedRecordLength() {

        byte[] payload = "test".getBytes(StandardCharsets.UTF_8);

        byte[] bytes = serialize(payload, 1L);

        int expectedLength =
                4 + 1 + 8 + 4 + payload.length + CHECKSUM_SIZE;

        assertEquals(expectedLength, bytes.length);

        ByteBuffer buffer = ByteBuffer.wrap(bytes)
                .order(ByteOrder.BIG_ENDIAN);

        assertEquals(expectedLength, buffer.getInt());
    }

    // ------------------------------------------------
    // Payload corruption tests
    // ------------------------------------------------

    @Test
    void shouldRejectModifiedPayload() {

        byte[] bytes = serialize(
                "original".getBytes(StandardCharsets.UTF_8),
                1L
        );

        bytes[PAYLOAD_OFFSET] ^= 0x01;

        assertInvalid(bytes);
    }

    @Test
    void shouldRejectModifiedChecksum() {

        byte[] bytes = serialize(
                "original".getBytes(StandardCharsets.UTF_8),
                1L
        );

        bytes[bytes.length - 1] ^= 0x01;

        assertInvalid(bytes);
    }

    // ------------------------------------------------
    // Header corruption tests
    // ------------------------------------------------

    @Test
    void shouldRejectInvalidVersion() {

        byte[] bytes = serialize(
                "test".getBytes(StandardCharsets.UTF_8),
                1L
        );

        bytes[VERSION_OFFSET] = (byte) 127;

        assertInvalid(bytes);
    }

    @Test
    void shouldRejectModifiedSequenceNumber() {

        byte[] bytes = serialize(
                "test".getBytes(StandardCharsets.UTF_8),
                1L
        );

        bytes[SEQUENCE_OFFSET] ^= 0x01;

        assertInvalid(bytes);
    }

    @Test
    void shouldRejectNegativePayloadLength() {

        byte[] bytes = serialize(
                "test".getBytes(StandardCharsets.UTF_8),
                1L
        );

        ByteBuffer.wrap(bytes)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(PAYLOAD_LENGTH_OFFSET, -1);

        assertInvalid(bytes);
    }

    @Test
    void shouldRejectExcessivePayloadLength() {

        byte[] bytes = serialize(
                "test".getBytes(StandardCharsets.UTF_8),
                1L
        );

        ByteBuffer.wrap(bytes)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(PAYLOAD_LENGTH_OFFSET, Integer.MAX_VALUE);

        assertInvalid(bytes);
    }

    // ------------------------------------------------
    // Record length tests
    // ------------------------------------------------

    @Test
    void shouldRejectIncorrectRecordLength() {

        byte[] bytes = serialize(
                "test".getBytes(StandardCharsets.UTF_8),
                1L
        );

        ByteBuffer.wrap(bytes)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(RECORD_LENGTH_OFFSET, 10);

        assertInvalid(bytes);
    }

    @Test
    void shouldRejectTruncatedRecordLength() {

        byte[] bytes = serialize(
                "test".getBytes(StandardCharsets.UTF_8),
                1L
        );

        assertInvalid(Arrays.copyOf(bytes, 2));
    }

    // ------------------------------------------------
    // Partial record tests
    // ------------------------------------------------

    @Test
    void shouldRejectEmptyBuffer() {

        assertInvalid(new byte[0]);
    }

    @Test
    void shouldRejectRecordWithOnlyVersion() {

        byte[] bytes = serialize(
                "test".getBytes(StandardCharsets.UTF_8),
                1L
        );

        assertInvalid(Arrays.copyOf(bytes, 5));
    }

    @Test
    void shouldRejectRecordMissingSequenceNumber() {

        byte[] bytes = serialize(
                "test".getBytes(StandardCharsets.UTF_8),
                1L
        );

        assertInvalid(Arrays.copyOf(bytes, 10));
    }

    @Test
    void shouldRejectRecordMissingPayloadLength() {

        byte[] bytes = serialize(
                "test".getBytes(StandardCharsets.UTF_8),
                1L
        );

        assertInvalid(Arrays.copyOf(bytes, 15));
    }

    @Test
    void shouldRejectRecordWithPartialPayload() {

        byte[] bytes = serialize(
                "test payload".getBytes(StandardCharsets.UTF_8),
                1L
        );

        assertInvalid(Arrays.copyOf(bytes, PAYLOAD_OFFSET + 3));
    }

    @Test
    void shouldRejectRecordMissingChecksum() {

        byte[] bytes = serialize(
                "test".getBytes(StandardCharsets.UTF_8),
                1L
        );

        assertInvalid(Arrays.copyOf(bytes, bytes.length - 1));
    }

    // ------------------------------------------------
    // Unexpected input tests
    // ------------------------------------------------

    @Test
    void shouldRejectRandomGarbage() {

        byte[] garbage = new byte[100];

        new Random(42).nextBytes(garbage);

        assertInvalid(garbage);
    }

    @Test
    void shouldRejectAllZeroBytes() {

        byte[] garbage = new byte[100];

        assertInvalid(garbage);
    }
}
