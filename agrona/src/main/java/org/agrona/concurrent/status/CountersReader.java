/*
 * Copyright 2014-2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.agrona.concurrent.status;

import org.agrona.BitUtil;
import org.agrona.DirectBuffer;
import org.agrona.collections.IntObjConsumer;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.agrona.BitUtil.CACHE_LINE_LENGTH;
import static org.agrona.BitUtil.SIZE_OF_INT;

/**
 * Reads the counters metadata and values buffers.
 * <p>
 * This class is threadsafe and can be used across threads.
 * <p>
 * <b>Values Buffer</b>
 * <pre>
 *   0                   1                   2                   3
 *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |                        Counter Value                          |
 *  |                                                               |
 *  +---------------------------------------------------------------+
 *  |                     120 bytes of padding                     ...
 * ...                                                              |
 *  +---------------------------------------------------------------+
 *  |                   Repeats to end of buffer                   ...
 *  |                                                               |
 * ...                                                              |
 *  +---------------------------------------------------------------+
 * </pre>
 * <p>
 * <b>Meta Data Buffer</b>
 * <pre>
 *   0                   1                   2                   3
 *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |                        Record State                           |
 *  +---------------------------------------------------------------+
 *  |                          Type Id                              |
 *  +---------------------------------------------------------------+
 *  |                      120 bytes for key                       ...
 * ...                                                              |
 *  +-+-------------------------------------------------------------+
 *  |R|                      Label Length                           |
 *  +-+-------------------------------------------------------------+
 *  |                     380 bytes of Label                       ...
 * ...                                                              |
 *  +---------------------------------------------------------------+
 *  |                   Repeats to end of buffer                   ...
 *  |                                                               |
 * ...                                                              |
 *  +---------------------------------------------------------------+
 * </pre>
 */
public class CountersReader
{
    /**
     * Callback function for consuming metadata records of counters.
     */
    @FunctionalInterface
    public interface MetaData
    {
        /**
         * Accept a metadata record.
         *
         * @param counterId of the counter.
         * @param typeId    of the counter.
         * @param keyBuffer for the counter.
         * @param label     for the counter.
         */
        void accept(int counterId, int typeId, DirectBuffer keyBuffer, String label);
    }

    /**
     * Record has not been used.
     */
    public static final int RECORD_UNUSED = 0;

    /**
     * Record currently allocated for use..
     */
    public static final int RECORD_ALLOCATED = 1;

    /**
     * Record was active and now has been reclaimed.
     */
    public static final int RECORD_RECLAIMED = -1;

    /**
     * Offset in the record at which the type id field is stored.
     */
    public static final int TYPE_ID_OFFSET = SIZE_OF_INT;

    /**
     * Offset in the record at which the key is stored.
     */
    public static final int KEY_OFFSET = TYPE_ID_OFFSET + SIZE_OF_INT;

    /**
     * Offset in the record at which the label is stored.
     */
    public static final int LABEL_OFFSET = BitUtil.CACHE_LINE_LENGTH * 2;

    /**
     * Length of a counter label length including length prefix.
     */
    public static final int FULL_LABEL_LENGTH = BitUtil.CACHE_LINE_LENGTH * 6;

    /**
     * Maximum length of a label not including its length prefix.
     */
    public static final int MAX_LABEL_LENGTH = FULL_LABEL_LENGTH - SIZE_OF_INT;

    /**
     * Maximum length a key can be.
     */
    public static final int MAX_KEY_LENGTH = (CACHE_LINE_LENGTH * 2) - (SIZE_OF_INT * 2);

    /**
     * Length of a meta data record in bytes.
     */
    public static final int METADATA_LENGTH = LABEL_OFFSET + FULL_LABEL_LENGTH;

    /**
     * Length of the space allocated to a counter that includes padding to avoid false sharing.
     */
    public static final int COUNTER_LENGTH = BitUtil.CACHE_LINE_LENGTH * 2;

    protected final AtomicBuffer metaDataBuffer;
    protected final AtomicBuffer valuesBuffer;
    protected final Charset labelCharset;

    /**
     * Construct a reader over buffers containing the values and associated metadata.
     * <p>
     * Counter labels default to {@link StandardCharsets#UTF_8}.
     *
     * @param metaDataBuffer containing the counter metadata.
     * @param valuesBuffer   containing the counter values.
     */
    public CountersReader(final AtomicBuffer metaDataBuffer, final AtomicBuffer valuesBuffer)
    {
        this(metaDataBuffer, valuesBuffer, StandardCharsets.UTF_8);
    }

    /**
     * Construct a reader over buffers containing the values and associated metadata.
     *
     * @param metaDataBuffer containing the counter metadata.
     * @param valuesBuffer   containing the counter values.
     * @param labelCharset   for the label encoding.
     */
    public CountersReader(
        final AtomicBuffer metaDataBuffer, final AtomicBuffer valuesBuffer, final Charset labelCharset)
    {
        this.valuesBuffer = valuesBuffer;
        this.metaDataBuffer = metaDataBuffer;
        this.labelCharset = labelCharset;
    }

    /**
     * Get the buffer containing the metadata for the counters.
     *
     * @return the buffer containing the metadata for the counters.
     */
    public AtomicBuffer metaDataBuffer()
    {
        return metaDataBuffer;
    }

    /**
     * Get the buffer containing the values for the counters.
     *
     * @return the buffer containing the values for the counters.
     */
    public AtomicBuffer valuesBuffer()
    {
        return valuesBuffer;
    }

    /**
     * The {@link Charset} used for the encoded label.
     *
     * @return the {@link Charset} used for the encoded label.
     */
    public Charset labelCharset()
    {
        return labelCharset;
    }

    /**
     * The offset in the counter buffer for a given counterId.
     *
     * @param counterId for which the offset should be provided.
     * @return the offset in the counter buffer.
     */
    public static int counterOffset(final int counterId)
    {
        return counterId * COUNTER_LENGTH;
    }

    /**
     * The offset in the metadata buffer for a given id.
     *
     * @param counterId for the record.
     * @return the offset at which the metadata record begins.
     */
    public static int metaDataOffset(final int counterId)
    {
        return counterId * METADATA_LENGTH;
    }

    /**
     * Iterate over all labels in the label buffer.
     *
     * @param consumer function to be called for each label.
     */
    public void forEach(final IntObjConsumer<String> consumer)
    {
        int counterId = 0;

        for (int i = 0, capacity = metaDataBuffer.capacity(); i < capacity; i += METADATA_LENGTH)
        {
            final int recordStatus = metaDataBuffer.getIntVolatile(i);

            if (RECORD_ALLOCATED == recordStatus)
            {
                final String label = labelValue(i);
                consumer.accept(counterId, label);
            }
            else if (RECORD_UNUSED == recordStatus)
            {
                break;
            }

            counterId++;
        }
    }

    /**
     * Iterate over all the metadata in the buffer.
     *
     * @param metaData function to be called for each metadata record.
     */
    public void forEach(final MetaData metaData)
    {
        int counterId = 0;

        for (int i = 0, capacity = metaDataBuffer.capacity(); i < capacity; i += METADATA_LENGTH)
        {
            final int recordStatus = metaDataBuffer.getIntVolatile(i);
            if (RECORD_ALLOCATED == recordStatus)
            {
                final int typeId = metaDataBuffer.getInt(i + TYPE_ID_OFFSET);
                final String label = labelValue(i);
                final DirectBuffer keyBuffer = new UnsafeBuffer(metaDataBuffer, i + KEY_OFFSET, MAX_KEY_LENGTH);

                metaData.accept(counterId, typeId, keyBuffer, label);
            }
            else if (RECORD_UNUSED == recordStatus)
            {
                break;
            }

            counterId++;
        }
    }

    /**
     * Get the value for a given counter id as a volatile read.
     *
     * @param counterId to be read.
     * @return the current value of the counter.
     */
    public long getCounterValue(final int counterId)
    {
        return valuesBuffer.getLongVolatile(counterOffset(counterId));
    }

    private String labelValue(final int recordOffset)
    {
        final int labelLength = metaDataBuffer.getInt(recordOffset + LABEL_OFFSET);
        final byte[] stringInBytes = new byte[labelLength];
        metaDataBuffer.getBytes(recordOffset + LABEL_OFFSET + SIZE_OF_INT, stringInBytes);

        return new String(stringInBytes, labelCharset);
    }
}
