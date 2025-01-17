/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.db.streaming;

import java.io.IOError;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.ListIterator;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.UnmodifiableIterator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.DeletionTime;
import org.apache.cassandra.db.Directories;
import org.apache.cassandra.db.RegularAndStaticColumns;
import org.apache.cassandra.db.SerializationHeader;
import org.apache.cassandra.db.lifecycle.LifecycleNewTracker;
import org.apache.cassandra.db.rows.DeserializationHelper;
import org.apache.cassandra.db.rows.EncodingStats;
import org.apache.cassandra.db.rows.Row;
import org.apache.cassandra.db.rows.Unfiltered;
import org.apache.cassandra.db.rows.UnfilteredRowIterator;
import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.exceptions.UnknownColumnException;
import org.apache.cassandra.io.sstable.SSTableMultiWriter;
import org.apache.cassandra.io.sstable.SSTableSimpleIterator;
import org.apache.cassandra.io.sstable.format.RangeAwareSSTableWriter;
import org.apache.cassandra.io.sstable.format.SSTableFormat;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.io.sstable.format.Version;
import org.apache.cassandra.io.util.DataInputPlus;
import org.apache.cassandra.io.util.RewindableDataInputStreamPlus;
import org.apache.cassandra.io.util.TrackedDataInputPlus;
import org.apache.cassandra.metrics.StorageMetrics;
import org.apache.cassandra.schema.TableId;
import org.apache.cassandra.schema.TableMetadata;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.streaming.ProgressInfo;
import org.apache.cassandra.streaming.StreamReceivedOutOfTokenRangeException;
import org.apache.cassandra.streaming.StreamReceiver;
import org.apache.cassandra.streaming.StreamSession;
import org.apache.cassandra.streaming.compress.StreamCompressionInputStream;
import org.apache.cassandra.streaming.messages.StreamMessageHeader;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.NoSpamLogger;

import static org.apache.cassandra.net.MessagingService.current_version;

/**
 * CassandraStreamReader reads from stream and writes to SSTable.
 */
public class CassandraStreamReader implements IStreamReader
{
    private static final Logger logger = LoggerFactory.getLogger(CassandraStreamReader.class);
    private static final String logMessageTemplate = "[Stream #{}] Received streamed SSTable {} from {} containing key(s) outside valid ranges {}. Example: {}";
    protected final TableId tableId;
    protected final long estimatedKeys;
    protected final Collection<SSTableReader.PartitionPositionBounds> sections;
    protected final StreamSession session;
    protected final Version inputVersion;
    protected final long repairedAt;
    protected final UUID pendingRepair;
    protected final SSTableFormat.Type format;
    protected final int sstableLevel;
    protected final SerializationHeader.Component header;
    protected final int fileSeqNum;

    public CassandraStreamReader(StreamMessageHeader header, CassandraStreamHeader streamHeader, StreamSession session)
    {
        if (session.getPendingRepair() != null)
        {
            // we should only ever be streaming pending repair
            // sstables if the session has a pending repair id
            assert session.getPendingRepair().equals(header.pendingRepair);
        }
        this.session = session;
        this.tableId = header.tableId;
        this.estimatedKeys = streamHeader.estimatedKeys;
        this.sections = streamHeader.sections;
        this.inputVersion = streamHeader.version;
        this.repairedAt = header.repairedAt;
        this.pendingRepair = header.pendingRepair;
        this.format = streamHeader.format;
        this.sstableLevel = streamHeader.sstableLevel;
        this.header = streamHeader.serializationHeader;
        this.fileSeqNum = header.sequenceNumber;
    }

    /**
     * @param inputPlus where this reads data from
     * @return SSTable transferred
     * @throws IOException if reading the remote sstable fails. Will throw an RTE if local write fails.
     */
    @SuppressWarnings("resource") // input needs to remain open, streams on top of it can't be closed
    @Override
    public SSTableMultiWriter read(DataInputPlus inputPlus) throws IOException
    {
        long totalSize = totalSize();

        ColumnFamilyStore cfs = ColumnFamilyStore.getIfExists(tableId);
        if (cfs == null)
        {
            // schema was dropped during streaming
            throw new IOException("CF " + tableId + " was dropped during streaming");
        }

        logger.debug("[Stream #{}] Start receiving file #{} from {}, repairedAt = {}, size = {}, ks = '{}', table = '{}', pendingRepair = '{}'.",
                     session.planId(), fileSeqNum, session.peer, repairedAt, totalSize, cfs.keyspace.getName(),
                     cfs.getTableName(), pendingRepair);

        StreamDeserializer deserializer = null;
        SSTableMultiWriter writer = null;
        try (StreamCompressionInputStream streamCompressionInputStream = new StreamCompressionInputStream(inputPlus, current_version))
        {
            TrackedDataInputPlus in = new TrackedDataInputPlus(streamCompressionInputStream);
            writer = createWriter(cfs, totalSize, repairedAt, pendingRepair, format);
            deserializer = getDeserializer(cfs.metadata(), in, inputVersion, session, writer);
            while (in.getBytesRead() < totalSize)
            {
                writePartition(deserializer, writer);
                // TODO move this to BytesReadTracker
                session.progress(writer.getFilename() + '-' + fileSeqNum, ProgressInfo.Direction.IN, in.getBytesRead(), totalSize);
            }
            logger.debug("[Stream #{}] Finished receiving file #{} from {} readBytes = {}, totalSize = {}",
                         session.planId(), fileSeqNum, session.peer, FBUtilities.prettyPrintMemory(in.getBytesRead()), FBUtilities.prettyPrintMemory(totalSize));
            return writer;
        }
        catch (Throwable e)
        {
            Object partitionKey = deserializer != null ? deserializer.partitionKey() : "";
            logger.warn("[Stream {}] Error while reading partition {} from stream on ks='{}' and table='{}'.",
                        session.planId(), partitionKey, cfs.keyspace.getName(), cfs.getTableName(), e);
            if (writer != null)
            {
                writer.abort(e);
            }
            throw Throwables.propagate(e);
        }
    }

    protected StreamDeserializer getDeserializer(TableMetadata metadata,
                                                 TrackedDataInputPlus in,
                                                 Version inputVersion,
                                                 StreamSession session,
                                                 SSTableMultiWriter writer) throws IOException
    {
        return new StreamDeserializer(metadata, in, inputVersion, getHeader(metadata), session, writer);
    }

    protected SerializationHeader getHeader(TableMetadata metadata) throws UnknownColumnException
    {
        return header != null? header.toHeader(metadata) : null; //pre-3.0 sstable have no SerializationHeader
    }
    @SuppressWarnings("resource")
    protected SSTableMultiWriter createWriter(ColumnFamilyStore cfs, long totalSize, long repairedAt, UUID pendingRepair, SSTableFormat.Type format) throws IOException
    {
        Directories.DataDirectory localDir = cfs.getDirectories().getWriteableLocation(totalSize);
        if (localDir == null)
            throw new IOException(String.format("Insufficient disk space to store %s", FBUtilities.prettyPrintMemory(totalSize)));

        StreamReceiver streamReceiver = session.getAggregator(tableId);
        Preconditions.checkState(streamReceiver instanceof CassandraStreamReceiver);
        LifecycleNewTracker lifecycleNewTracker = CassandraStreamReceiver.fromReceiver(session.getAggregator(tableId)).createLifecycleNewTracker();

        RangeAwareSSTableWriter writer = new RangeAwareSSTableWriter(cfs, estimatedKeys, repairedAt, pendingRepair, false, format, sstableLevel, totalSize, lifecycleNewTracker, getHeader(cfs.metadata()));
        return writer;
    }

    protected long totalSize()
    {
        long size = 0;
        for (SSTableReader.PartitionPositionBounds section : sections)
            size += section.upperPosition - section.lowerPosition;
        return size;
    }

    protected void writePartition(StreamDeserializer deserializer, SSTableMultiWriter writer) throws IOException
    {
        writer.append(deserializer.newPartition());
        deserializer.checkForExceptions();
    }

    public static class StreamDeserializer extends UnmodifiableIterator<Unfiltered> implements UnfilteredRowIterator
    {
        private final TableMetadata metadata;
        private final DataInputPlus in;
        private final SerializationHeader header;
        private final DeserializationHelper helper;

        private final List<Range<Token>> ownedRanges;
        private final boolean outOfRangeTokenLogging;
        private final boolean outOfRangeTokenRejection;
        private final StreamSession session;
        private final SSTableMultiWriter writer;

        private int lastCheckedRangeIndex;
        protected DecoratedKey key;
        protected DeletionTime partitionLevelDeletion;
        protected SSTableSimpleIterator iterator;
        protected Row staticRow;
        private IOException exception;

        public StreamDeserializer(TableMetadata metadata, DataInputPlus in, Version version, SerializationHeader header, StreamSession session, SSTableMultiWriter writer) throws IOException
        {
            this.metadata = metadata;
            this.in = in;
            this.helper = new DeserializationHelper(metadata, version.correspondingMessagingVersion(), DeserializationHelper.Flag.PRESERVE_SIZE);
            this.header = header;
            this.session = session;
            this.writer = writer;

            ownedRanges = Range.normalize(StorageService.instance.getLocalAndPendingRanges(metadata.keyspace));
            lastCheckedRangeIndex = 0;
            outOfRangeTokenLogging = DatabaseDescriptor.getLogOutOfTokenRangeRequests();
            outOfRangeTokenRejection = DatabaseDescriptor.getRejectOutOfTokenRangeRequests();
        }

        public UnfilteredRowIterator newPartition() throws IOException
        {
            readKey();
            readPartition();
            return this;
        }

        protected void readKey() throws IOException
        {
            key = metadata.partitioner.decorateKey(ByteBufferUtil.readWithShortLength(in));

            lastCheckedRangeIndex = verifyKeyInOwnedRanges(key,
                                                           ownedRanges,
                                                           lastCheckedRangeIndex,
                                                           outOfRangeTokenLogging,
                                                           outOfRangeTokenRejection);
        }

        protected void readPartition() throws IOException
        {
            partitionLevelDeletion = DeletionTime.serializer.deserialize(in);
            iterator = SSTableSimpleIterator.create(metadata, in, header, helper, partitionLevelDeletion);
            staticRow = iterator.readStaticRow();
        }

        public TableMetadata metadata()
        {
            return metadata;
        }

        public RegularAndStaticColumns columns()
        {
            // We don't know which columns we'll get so assume it can be all of them
            return metadata.regularAndStaticColumns();
        }

        public boolean isReverseOrder()
        {
            return false;
        }

        public DecoratedKey partitionKey()
        {
            return key;
        }

        public DeletionTime partitionLevelDeletion()
        {
            return partitionLevelDeletion;
        }

        public Row staticRow()
        {
            return staticRow;
        }

        public EncodingStats stats()
        {
            return header.stats();
        }

        public boolean hasNext()
        {
            try
            {
                return iterator.hasNext();
            }
            catch (IOError e)
            {
                if (e.getCause() != null && e.getCause() instanceof IOException)
                {
                    exception = (IOException)e.getCause();
                    return false;
                }
                throw e;
            }
        }

        public Unfiltered next()
        {
            // Note that in practice we know that IOException will be thrown by hasNext(), because that's
            // where the actual reading happens, so we don't bother catching RuntimeException here (contrarily
            // to what we do in hasNext)
            Unfiltered unfiltered = iterator.next();
            return metadata.isCounter() && unfiltered.kind() == Unfiltered.Kind.ROW
                   ? maybeMarkLocalToBeCleared((Row) unfiltered)
                   : unfiltered;
        }

        private Row maybeMarkLocalToBeCleared(Row row)
        {
            return metadata.isCounter() ? row.markCounterLocalToBeCleared() : row;
        }

        public void checkForExceptions() throws IOException
        {
            if (exception != null)
                throw exception;
        }

        public void close()
        {
        }

        /* We have a separate cleanup method because sometimes close is called before exhausting the
           StreamDeserializer (for instance, when enclosed in an try-with-resources wrapper, such as in
           BigTableWriter.append()).
         */
        public void cleanup()
        {
            if (in instanceof RewindableDataInputStreamPlus)
            {
                try
                {
                    ((RewindableDataInputStreamPlus) in).close(false);
                }
                catch (IOException e)
                {
                    logger.warn("Error while closing RewindableDataInputStreamPlus.", e);
                }
            }
        }

        private int verifyKeyInOwnedRanges(final DecoratedKey key,
                                           List<Range<Token>> ownedRanges,
                                           int lastCheckedRangeIndex,
                                           boolean outOfRangeTokenLogging,
                                           boolean outOfRangeTokenRejection)
        {
            if (lastCheckedRangeIndex < ownedRanges.size())
            {
                ListIterator<Range<Token>> rangesToCheck = ownedRanges.listIterator(lastCheckedRangeIndex);
                while (rangesToCheck.hasNext())
                {
                    Range<Token> range = rangesToCheck.next();
                    if (range.contains(key.getToken()))
                        return lastCheckedRangeIndex;

                    lastCheckedRangeIndex++;
                }
            }

            StorageMetrics.totalOpsForInvalidToken.inc();

            if (outOfRangeTokenLogging)
                NoSpamLogger.log(logger, NoSpamLogger.Level.WARN, 1, TimeUnit.SECONDS, logMessageTemplate, session.planId(), writer.getFilename(), session.peer, ownedRanges, key);

            if (outOfRangeTokenRejection)
                throw new StreamReceivedOutOfTokenRangeException(ownedRanges, key, writer.getFilename());

            return lastCheckedRangeIndex;
        }
    }
}
