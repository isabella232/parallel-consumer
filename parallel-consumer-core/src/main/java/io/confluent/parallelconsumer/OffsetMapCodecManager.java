package io.confluent.parallelconsumer;

/*-
 * Copyright (C) 2020 Confluent, Inc.
 */

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import pl.tlinkowski.unij.api.UniSets;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static io.confluent.csid.utils.Range.range;
import static io.confluent.csid.utils.StringUtils.msg;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Uses multiple encodings to compare, when decided, can refactor other options out for analysis only - {@link
 * #encodeOffsetsCompressed}
 * <p>
 * TODO: consider IO exception management - question sneaky throws usage?
 * <p>
 * TODO: enforce max uncommitted {@literal <} encoding length (Short.MAX)
 * <p>
 * Bitset serialisation format:
 * <ul>
 * <li>byte1: magic
 * <li>byte2-3: Short: bitset size
 * <li>byte4-n: serialised {@link BitSet}
 * </ul>
 */
@Slf4j
public class OffsetMapCodecManager<K, V> {

    /**
     * Maximum size of the commit offset metadata
     *
     * @see <a href="https://github.com/apache/kafka/blob/9bc9a37e50e403a356a4f10d6df12e9f808d4fba/core/src/main/scala/kafka/coordinator/group/OffsetConfig.scala#L52">OffsetConfig#DefaultMaxMetadataSize</a>
     * @see "kafka.coordinator.group.OffsetConfig#DefaultMaxMetadataSize"
     */
    public static final int DefaultMaxMetadataSize = 4096;

    public static final Charset CHARSET_TO_USE = UTF_8;

    private final WorkManager<K, V> wm;

    org.apache.kafka.clients.consumer.Consumer<K, V> consumer;

    /**
     * Forces the use of a specific codec, instead of choosing the most efficient one. Useful for testing.
     */
    static Optional<OffsetEncoding> forcedCodec = Optional.empty();

    public OffsetMapCodecManager(final WorkManager<K, V> wm, final org.apache.kafka.clients.consumer.Consumer<K, V> consumer) {
        this.wm = wm;
        this.consumer = consumer;
    }

    /**
     * Load all the previously completed offsets that were not committed
     */
    void loadAllAssignedOffsetMap() {
        Set<TopicPartition> assignment = consumer.assignment();
        loadOffsetMapForPartition(assignment);
    }

    /**
     * Load all the previously completed offsets that were not committed
     */
    void loadOffsetMapForPartition(final Set<TopicPartition> assignment) {
        Map<TopicPartition, OffsetAndMetadata> committed = consumer.committed(assignment);
        committed.forEach((tp, offsetAndMeta) -> {
            if (offsetAndMeta != null) {
                long offset = offsetAndMeta.offset();
                String metadata = offsetAndMeta.metadata();
                try {
                    loadOffsetMetadataPayload(offset, tp, metadata);
                } catch (OffsetDecodingError offsetDecodingError) {
                    log.error("Error decoding offsets from assigned partition, dropping offset map (will replay previously completed messages - partition: {}, data: {})",
                            tp, offsetAndMeta, offsetDecodingError);
                }
            }
        });
    }

    static ParallelConsumer.Tuple<Long, TreeSet<Long>> deserialiseIncompleteOffsetMapFromBase64(long finalBaseComittedOffsetForPartition, String incompleteOffsetMap) throws OffsetDecodingError {
        byte[] decodedBytes;
        try {
            decodedBytes = OffsetSimpleSerialisation.decodeBase64(incompleteOffsetMap);
        } catch (IllegalArgumentException a) {
            throw new OffsetDecodingError(msg("Error decoding offset metadata, input was: {}", incompleteOffsetMap), a);
        }
        ParallelConsumer.Tuple<Long, Set<Long>> incompleteOffsets = decodeCompressedOffsets(finalBaseComittedOffsetForPartition, decodedBytes);
        TreeSet<Long> longs = new TreeSet<>(incompleteOffsets.getRight());
        return ParallelConsumer.Tuple.pairOf(incompleteOffsets.getLeft(), longs);
    }

    void loadOffsetMetadataPayload(long startOffset, TopicPartition tp, String offsetMetadataPayload) throws OffsetDecodingError {
        ParallelConsumer.Tuple<Long, TreeSet<Long>> incompletes = deserialiseIncompleteOffsetMapFromBase64(startOffset, offsetMetadataPayload);
        wm.raisePartitionHighWaterMark(incompletes.getLeft(), tp);
        wm.partitionIncompleteOffsets.put(tp, incompletes.getRight());
    }

    String makeOffsetMetadataPayload(long finalOffsetForPartition, TopicPartition tp, Set<Long> incompleteOffsets) throws EncodingNotSupportedException {
        String offsetMap = serialiseIncompleteOffsetMapToBase64(finalOffsetForPartition, tp, incompleteOffsets);
        return offsetMap;
    }

    String serialiseIncompleteOffsetMapToBase64(long finalOffsetForPartition, TopicPartition tp, Set<Long> incompleteOffsets) throws EncodingNotSupportedException {
        byte[] compressedEncoding = encodeOffsetsCompressed(finalOffsetForPartition, tp, incompleteOffsets);
        String b64 = OffsetSimpleSerialisation.base64(compressedEncoding);
        return b64;
    }

    /**
     * Print out all the offset status into a String, and use X to effectively do run length encoding compression on the
     * string.
     * <p>
     * Include the magic byte in the returned array.
     * <p>
     * Can remove string encoding in favour of the boolean array for the `BitSet` if that's how things settle.
     */
    byte[] encodeOffsetsCompressed(long finalOffsetForPartition, TopicPartition tp, Set<Long> incompleteOffsets) throws EncodingNotSupportedException {
        Long nextExpectedOffset = wm.partitionOffsetHighWaterMarks.get(tp) + 1;
        OffsetSimultaneousEncoder simultaneousEncoder = new OffsetSimultaneousEncoder(finalOffsetForPartition, nextExpectedOffset, incompleteOffsets).invoke();
        if (forcedCodec.isPresent()) {
            OffsetEncoding forcedOffsetEncoding = forcedCodec.get();
            log.warn("Forcing use of {}, for testing", forcedOffsetEncoding);
            Map<OffsetEncoding, byte[]> encodingMap = simultaneousEncoder.getEncodingMap();
            byte[] bytes = encodingMap.get(forcedOffsetEncoding);
            if (bytes == null)
                throw new EncodingNotSupportedException(msg("Can't force an encoding that hasn't been run: {}", forcedOffsetEncoding));
            return simultaneousEncoder.packEncoding(new EncodedOffsetPair(forcedOffsetEncoding, ByteBuffer.wrap(bytes)));
        } else {
            return simultaneousEncoder.packSmallest();
        }
    }

    /**
     * Print out all the offset status into a String, and potentially use zstd to effectively do run length encoding
     * compression
     *
     * @return Set of offsets which are not complete.
     */
    static ParallelConsumer.Tuple<Long, Set<Long>> decodeCompressedOffsets(long finalOffsetForPartition, byte[] decodedBytes) {
        if (decodedBytes.length == 0) {
            // no offset bitmap data
            return ParallelConsumer.Tuple.pairOf(finalOffsetForPartition, UniSets.of());
        }

        EncodedOffsetPair result = EncodedOffsetPair.unwrap(decodedBytes);

        ParallelConsumer.Tuple<Long, Set<Long>> incompletesTuple = result.getDecodedIncompletes(finalOffsetForPartition);

        Set<Long> incompletes = incompletesTuple.getRight();
        long highWater = incompletesTuple.getLeft();

        ParallelConsumer.Tuple<Long, Set<Long>> tuple = ParallelConsumer.Tuple.pairOf(highWater, incompletes);
        return tuple;
    }

    String incompletesToBitmapString(long finalOffsetForPartition, TopicPartition tp, Set<Long> incompleteOffsets) {
        StringBuilder runLengthString = new StringBuilder();
        Long lowWaterMark = finalOffsetForPartition;
        Long highWaterMark = wm.partitionOffsetHighWaterMarks.get(tp);
        long end = highWaterMark - lowWaterMark;
        for (final var relativeOffset : range(end)) {
            long offset = lowWaterMark + relativeOffset;
            if (incompleteOffsets.contains(offset)) {
                runLengthString.append("o");
            } else {
                runLengthString.append("x");
            }
        }
        return runLengthString.toString();
    }

    static Set<Long> bitmapStringToIncomplete(final long baseOffset, final String inputBitmapString) {
        final Set<Long> incompleteOffsets = new HashSet<>();

        final long longLength = inputBitmapString.length();
        range(longLength).forEach(i -> {
            char bit = inputBitmapString.charAt(i);
            if (bit == 'o') {
                incompleteOffsets.add(baseOffset + i);
            } else if (bit == 'x') {
                log.trace("Dropping completed offset");
            } else {
                throw new IllegalArgumentException("Invalid encoding - unexpected char: " + bit);
            }
        });

        return incompleteOffsets;
    }

}
