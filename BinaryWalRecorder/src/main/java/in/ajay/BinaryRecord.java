package in.ajay;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

public class BinaryRecord {
    /*
            +------------+---------+----------+-------------+
            | Record len | Version | Sequence | Payload Len |
            | 4 bytes    | 1 byte  | 8 bytes  | 4 bytes     |
            +------------+---------+----------+-------------+
            |           Payload(UTF-8 encoded string        |
            +------------------------------------------------+
            |                  CRC32C                        |
            |                  8 bytes                       |
            +------------------------------------------------+
     */

    private byte[] payload;
    private long sequenceNumber;

    private final static int maxPayloadSize = 1024*1024;
    private final static byte allowedRecordVersion = 1;
    private static int minPayloadSize = 3;

    final static int RecordLengthBytes = 4;
    private final static int VersionBytes = 1;
    private final static int SequenceNumberBytes = 8;
    private final static int PayloadLengthBytes = 4;
    private final static int CheckSumBytes = 8;
    private final static int minimumRecordLength = 4 + 1+ 8+ 4+ 8 + minPayloadSize;
    private final static int maximumRecordLength = 4 + 1+ 8+ 4+ 8 + maxPayloadSize;
    private final static int binaryRecordVersion = AppConfig.getInt("wal.recordVersion");


    public BinaryRecord(byte[] payload, long sequenceNumber) {
        this.payload = payload;
        this.sequenceNumber = sequenceNumber;
    }

    public byte[] getPayload() {
        return payload;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public  byte[] getByteArrayForRecord()
    {
        int recordLength = RecordLengthBytes + VersionBytes + SequenceNumberBytes + PayloadLengthBytes + payload.length + CheckSumBytes;

        ByteBuffer buffer = ByteBuffer.allocate(recordLength).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(recordLength);
        buffer.put((byte)binaryRecordVersion);
        buffer.putLong(sequenceNumber);
        buffer.putInt(payload.length);
        buffer.put(payload);
        byte[] bytesSofar = new byte[recordLength-CheckSumBytes];
        System.arraycopy(buffer.array(), 0, bytesSofar, 0, recordLength - CheckSumBytes);
        long checksum = getCRC32Checksum(bytesSofar);
        buffer.putLong(checksum);

        return buffer.array();
    }

    public static BinaryRecord getRecordFromByteArray(InputStream inputStream) throws IOException {

        ByteBuffer lengthBuffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        int bytesRead = inputStream.readNBytes(lengthBuffer.array(), 0, 4);

        if (bytesRead < 4 && bytesRead > 0) {
            throw new EOFException("corrupt record, exiting recover");
        } else if (bytesRead == 0) {
            return null;
        }

        int recordLength = lengthBuffer.getInt();

        if(recordLength<minimumRecordLength || recordLength>maximumRecordLength){
            throw new IllegalArgumentException("record length out of bounds");
        }

        ByteBuffer byteBuffer = ByteBuffer.allocate(recordLength -RecordLengthBytes).order(ByteOrder.BIG_ENDIAN);


        bytesRead = inputStream.readNBytes(byteBuffer.array(), 0, recordLength - RecordLengthBytes);

        if (bytesRead < recordLength - RecordLengthBytes) {
            throw new EOFException("bytes length is less than expected");
        }

        byte[] totalArray = ByteBuffer.allocate(recordLength-CheckSumBytes).order(ByteOrder.BIG_ENDIAN)
                .put(lengthBuffer.array())
                .put(byteBuffer.array(), 0, recordLength - RecordLengthBytes - CheckSumBytes)
                .array();
        long expectedCheckSum = getCRC32Checksum(totalArray);


        byte recordVersion = byteBuffer.get();
        if(recordVersion != allowedRecordVersion){
            throw new IllegalArgumentException("Unexpected record version");
        }

        long sequenceNumber = byteBuffer.getLong();
        int payLoadLength = byteBuffer.getInt();

        if(payLoadLength != recordLength - (RecordLengthBytes+VersionBytes+SequenceNumberBytes+PayloadLengthBytes+CheckSumBytes)){
            throw new IllegalArgumentException("Inconsistent payload length");
        }

        if(payLoadLength> maxPayloadSize || payLoadLength < minPayloadSize){
            throw new IllegalArgumentException("Payload exceeds maximum allowed size");
        }

        byte[] payload = new byte[payLoadLength];
        byteBuffer.get(payload, 0, payLoadLength);
        long actualCheckSum = byteBuffer.getLong();
        BinaryRecord record = new BinaryRecord(payload, sequenceNumber);

        if(actualCheckSum != expectedCheckSum){
            throw new IOException("Checksum mismatch. Invalid binary record");
        }
        return record;
    }

    public static long getCRC32Checksum(byte[] bytes) {
        Checksum crc32 = new CRC32();
        crc32.update(bytes, 0, bytes.length);
        return crc32.getValue();
    }
}
