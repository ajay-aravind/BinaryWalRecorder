import in.ajay.AppConfig;
import in.ajay.BinaryRecord;
import in.ajay.WalFlushMode;
import in.ajay.WriteAheadLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("performance")
class WriteAheadLogPerformanceTest {

    private static final byte[] MAGIC_BYTES =
            new byte[]{0x7A, 0x77, 0x3F, 0x29};

    private static final int DEFAULT_TRANSACTION_COUNT = 1_000_000;
    private static final int DEFAULT_THREAD_COUNT = 25;
    private static final int PAYLOAD_SIZE = 100;

    private Path walPath;
    private WriteAheadLog wal;

    private int transactionCount;
    private byte[] payload;

    @BeforeEach
    void setUp() throws Exception {
        resetSingleton();

        walPath = Path.of(AppConfig.get("wal.fileName"));

        if (walPath.getParent() != null) {
            Files.createDirectories(walPath.getParent());
        }

        Files.deleteIfExists(walPath);
        Files.createFile(walPath);

        transactionCount = Integer.getInteger(
                "wal.perf.transactions",
                DEFAULT_TRANSACTION_COUNT
        );

        payload = new byte[PAYLOAD_SIZE];

        // Reuse a fixed payload to avoid measuring string creation.
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 127);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (wal != null) {
                wal.close();
            }
        } finally {
            resetSingleton();

            if (walPath != null) {
                Files.deleteIfExists(walPath);
            }
        }
    }

    private WriteAheadLog getWal() throws IOException {
        if (wal == null) {
            wal = WriteAheadLog.getWriteAheadLog();
        }

        return wal;
    }

    private void resetSingleton() throws Exception {
        Field field =
                WriteAheadLog.class.getDeclaredField("writeAheadLog");

        field.setAccessible(true);

        WriteAheadLog instance =
                (WriteAheadLog) field.get(null);

        if (instance != null) {
            try {
                instance.close();
            } catch (IOException ignored) {
                // Continue cleanup.
            }
        }

        field.set(null, null);
        wal = null;
    }

    private void report(
            String benchmark,
            long elapsedNanos,
            long transactions
    ) {
        double elapsedSeconds =
                elapsedNanos / 1_000_000_000.0;

        double transactionsPerSecond =
                transactions / elapsedSeconds;

        double averageMicros =
                elapsedNanos / 1_000.0 / transactions;

        System.out.printf(
                "%nWAL BENCHMARK: %s%n" +
                        "Transactions: %,d%n" +
                        "Elapsed: %.3f seconds%n" +
                        "Throughput: %,.0f transactions/sec%n" +
                        "Average: %.3f microseconds/transaction%n" +
                        "WAL size: %,d bytes%n",
                benchmark,
                transactions,
                elapsedSeconds,
                transactionsPerSecond,
                averageMicros,
                fileSize()
        );
    }

    private long fileSize() {
        try {
            return Files.size(walPath);
        } catch (IOException e) {
            return -1;
        }
    }

    // ---------------------------------------------------------
    // 1. Single-threaded synchronous append
    // ---------------------------------------------------------

    @Test
    void benchmarkOneMillionSynchronousAppends() throws Exception {
        WriteAheadLog instance = getWal();

        long start = System.nanoTime();

        for (int i = 0; i < transactionCount; i++) {
            instance.append(payload);
        }

        long elapsed = System.nanoTime() - start;

        assertEquals(
                transactionCount,
                instance.lastSequenceNumber()
        );

        report(
                "Single-thread AUTO append",
                elapsed,
                transactionCount
        );
    }

    // ---------------------------------------------------------
    // 2. Concurrent synchronous append
    // ---------------------------------------------------------

    @Test
    void benchmarkOneMillionSynchronousAppendsWithManyThreads()
            throws Exception {

        WriteAheadLog instance = getWal();

        int threadCount = Integer.getInteger(
                "wal.perf.threads",
                DEFAULT_THREAD_COUNT
        );

        ExecutorService executor =
                Executors.newFixedThreadPool(threadCount);

        CountDownLatch ready =
                new CountDownLatch(threadCount);

        CountDownLatch start =
                new CountDownLatch(1);

        AtomicLong completedTransactions =
                new AtomicLong();

        Future<?>[] futures = new Future<?>[threadCount];

        int transactionsPerThread =
                transactionCount / threadCount;

        int remainder =
                transactionCount % threadCount;

        try {
            for (int thread = 0; thread < threadCount; thread++) {
                final int assignedTransactions =
                        transactionsPerThread
                                + (thread < remainder ? 1 : 0);

                futures[thread] = executor.submit(() -> {
                    ready.countDown();

                    try {
                        start.await();

                        for (int i = 0; i < assignedTransactions; i++) {
                            instance.append(payload);
                            completedTransactions.incrementAndGet();
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }

            ready.await();

            long benchmarkStart = System.nanoTime();
            start.countDown();

            for (Future<?> future : futures) {
                future.get();
            }

            long elapsed =
                    System.nanoTime() - benchmarkStart;

            assertEquals(
                    transactionCount,
                    completedTransactions.get()
            );

            assertEquals(
                    transactionCount,
                    instance.lastSequenceNumber()
            );

            report(
                    "Multi-thread AUTO append (" +
                            threadCount + " threads)",
                    elapsed,
                    transactionCount
            );
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    // ---------------------------------------------------------
    // 3. Recovery of one million records
    // ---------------------------------------------------------

    @Test
    void benchmarkRecoveryOfOneMillionRecords()
            throws Exception {

        createWalFixture(transactionCount);

        WriteAheadLog instance = getWal();

        AtomicLong recoveredCount = new AtomicLong();

        long start = System.nanoTime();

        instance.recover(record ->
                recoveredCount.incrementAndGet()
        );

        long elapsed = System.nanoTime() - start;

        assertEquals(
                transactionCount,
                recoveredCount.get()
        );

        report(
                "Recovery of prebuilt WAL",
                elapsed,
                transactionCount
        );
    }

    private void createWalFixture(int recordCount)
            throws IOException {

        try (OutputStream output =
                     new BufferedOutputStream(
                             Files.newOutputStream(walPath)
                     )) {

            output.write(MAGIC_BYTES);

            for (int i = 1; i <= recordCount; i++) {
                BinaryRecord record =
                        new BinaryRecord(payload, i);

                output.write(
                        record.getByteArrayForRecord()
                );
            }
        }
    }

    // ---------------------------------------------------------
    // 4. Manual flush / batch-flush baseline
    // ---------------------------------------------------------

    @Test
    void benchmarkOneMillionAppendsWithSingleManualFlush()
            throws Exception {

        // MANUAL mode avoids force(false) on every append.
        WriteAheadLog instance =
                WriteAheadLog.getWriteAheadLog(
                        WalFlushMode.MANUAL
                );

        wal = instance;

        long start = System.nanoTime();

        for (int i = 0; i < transactionCount; i++) {
            instance.append(payload);
        }

        instance.flush();

        long elapsed = System.nanoTime() - start;

        assertEquals(
                transactionCount,
                instance.lastSequenceNumber()
        );

        report(
                "Manual append + one final flush",
                elapsed,
                transactionCount
        );
    }
}
