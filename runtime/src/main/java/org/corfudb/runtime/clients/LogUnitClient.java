package org.corfudb.runtime.clients;

import com.codahale.metrics.Timer;
import com.google.common.collect.Range;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import lombok.Getter;
import lombok.Setter;
import org.corfudb.protocols.logprotocol.LogEntry;
import org.corfudb.protocols.wireprotocol.*;
import org.corfudb.runtime.CorfuRuntime;
import org.corfudb.runtime.exceptions.*;
import org.corfudb.util.serializer.Serializers;

import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;


/**
 * A client to a LogUnit.
 * <p>
 * This class provides access to operations on a remote log unit.
 * Created by mwei on 12/10/15.
 */
public class LogUnitClient implements IClient {

    @Setter
    @Getter
    IClientRouter router;

    public String getHost() { return router.getHost(); }

    public Integer getPort() { return router.getPort(); }

    /** The handler and handlers which implement this client. */
    @Getter
    public ClientMsgHandler msgHandler = new ClientMsgHandler(this)
            .generateHandlers(MethodHandles.lookup(), this);

    /** Handle an WRITE_OK message.
     *
     * @param msg   Incoming Message
     * @param ctx   Context
     * @param r     Router
     * @return      True, since this indicates success.
     */
    @ClientHandler(type=CorfuMsgType.WRITE_OK)
    private static Object handleOK(CorfuMsg msg, ChannelHandlerContext ctx, IClientRouter r) {
        return true;
    }

    /** Handle an ERROR_TRIMMED message.
     *
     * @param msg   Incoming Message
     * @param ctx   Context
     * @param r     Router
     * @throws  Exception
     */
    @ClientHandler(type=CorfuMsgType.ERROR_TRIMMED)
    private static Object handleTrimmed(CorfuMsg msg, ChannelHandlerContext ctx, IClientRouter r)
    throws Exception
    {
        throw new Exception("Trimmed");
    }

    /** Handle an ERROR_OVERWRITE message.
     *
     * @param msg   Incoming Message
     * @param ctx   Context
     * @param r     Router
     * @throws      OverwriteException
     */
    @ClientHandler(type=CorfuMsgType.ERROR_OVERWRITE)
    private static Object handleOverwrite(CorfuMsg msg, ChannelHandlerContext ctx, IClientRouter r)
            throws Exception
    {
        throw new OverwriteException();
    }

    /** Handle an ERROR_DATA_OUTRANKED message.
     *
     * @param msg   Incoming Message
     * @param ctx   Context
     * @param r     Router
     * @throws      OverwriteException
     */
    @ClientHandler(type=CorfuMsgType.ERROR_DATA_OUTRANKED)
    private static Object handleDataOutranked(CorfuMsg msg, ChannelHandlerContext ctx, IClientRouter r)
            throws Exception
    {
        throw new DataOutrankedException();
    }



    @ClientHandler(type=CorfuMsgType.ERROR_VALUE_ADOPTED)
    private static Object handleValueAdoptedResponse(CorfuPayloadMsg<ReadResponse> msg,
                                             ChannelHandlerContext ctx, IClientRouter r) {
        throw new ValueAdoptedException(msg.getPayload());
    }

    /** Handle an ERROR_REPLEX_OVERWRITE message.
     *
     * @param msg   Incoming Message
     * @param ctx   Context
     * @param r     Router
     * @throws      OverwriteException
     */
    @ClientHandler(type=CorfuMsgType.ERROR_REPLEX_OVERWRITE)
    private static Object handleReplexOverwrite(CorfuMsg msg, ChannelHandlerContext ctx, IClientRouter r)
            throws Exception
    {
        throw new ReplexOverwriteException();
    }

    /** Handle an ERROR_OOS message.
     *
     * @param msg   Incoming Message
     * @param ctx   Context
     * @param r     Router
     * @throws      OutOfSpaceException
     */
    @ClientHandler(type=CorfuMsgType.ERROR_OOS)
    private static Object handleOOS(CorfuMsg msg, ChannelHandlerContext ctx, IClientRouter r)
            throws Exception
    {
        throw new OutOfSpaceException();
    }

    /** Handle an ERROR_RANK message.
     *
     * @param msg   Incoming Message
     * @param ctx   Context
     * @param r     Router
     * @throws      Exception
     */
    @ClientHandler(type=CorfuMsgType.ERROR_RANK)
    private static Object handleOutranked(CorfuMsg msg, ChannelHandlerContext ctx, IClientRouter r)
            throws Exception
    {
        throw new Exception("rank");
    }

    /** Handle an ERROR_NOENTRY message.
     *
     * @param msg   Incoming Message
     * @param ctx   Context
     * @param r     Router
     * @throws      Exception
     */
    @ClientHandler(type=CorfuMsgType.ERROR_NOENTRY)
    private static Object handleNoEntry(CorfuMsg msg, ChannelHandlerContext ctx, IClientRouter r)
            throws Exception
    {
        throw new Exception("Tried to write commit on a non-existent entry");
    }

    /** Handle a READ_RESPONSE message.
     *
     * @param msg   Incoming Message
     * @param ctx   Context
     * @param r     Router
     */
    @ClientHandler(type=CorfuMsgType.READ_RESPONSE)
    private static Object handleReadResponse(CorfuPayloadMsg<ReadResponse> msg,
                                             ChannelHandlerContext ctx, IClientRouter r) {
        return msg.getPayload();
    }

    /**
     * Handle a ERROR_DATA_CORRUPTION message
     * @param msg   Incoming Message
     * @param ctx   Context
     * @param r     Router
     */
    @ClientHandler(type=CorfuMsgType.ERROR_DATA_CORRUPTION)
    private static Object handleReadDataCorruption(CorfuPayloadMsg<ReadResponse> msg,
                                                   ChannelHandlerContext ctx, IClientRouter r)
    {
        throw new DataCorruptionException();
    }

    /**
     * Handle a TAIL_RESPONSE message
     * @param msg   Incoming Message
     * @param ctx   Context
     * @param r     Router
     */
    @ClientHandler(type=CorfuMsgType.TAIL_RESPONSE)
    private static Object handleTailResponse(CorfuPayloadMsg<Long> msg,
                                             ChannelHandlerContext ctx, IClientRouter r) {
        return msg.getPayload();
    }

    /**
     * Asynchronously write to the logging unit.
     *
     * @param address        The address to write to.
     * @param streams        The streams, if any, that this write belongs to.
     * @param rank           The rank of this write (used for quorum
     *                       replication).
     * @param writeObject    The object, pre-serialization, to write.
     * @param backpointerMap The map of backpointers to write.
     * @return A CompletableFuture which will complete with the WriteResult once the
     * write completes.
     */
    public CompletableFuture<Boolean> write(long address, Set<UUID> streams, IMetadata.DataRank rank,
                                            Object writeObject, Map<UUID, Long> backpointerMap) {
        Timer.Context context = getTimerContext("writeObject");
        ByteBuf payload = Unpooled.buffer();
        Serializers.CORFU.serialize(writeObject, payload);
        WriteRequest wr = new WriteRequest(WriteMode.NORMAL, null, payload);
        wr.setStreams(streams);
        wr.setRank(rank);
        wr.setBackpointerMap(backpointerMap);
        wr.setGlobalAddress(address);
        CompletableFuture<Boolean> cf = router.sendMessageAndGetCompletable(CorfuMsgType.WRITE.payloadMsg(wr));
        return cf.thenApply(x -> { context.stop(); return x; });
    }

    /**
     * Asynchronously write an empty payload to the logging unit with ranked address space.
     * Used from the quorum replication when filling holes or during the first phase of the recovery write.
     *
     * @param address        The address to write to.
     * @param type           The data type
     * @param streams        The streams, if any, that this write belongs to.
     * @param rank           The rank of this write]
     */
    public CompletableFuture<Boolean> writeEmptyData(long address, DataType type, Set<UUID> streams, IMetadata.DataRank rank) {
        Timer.Context context = getTimerContext("writeObject");
        LogEntry entry = new LogEntry(LogEntry.LogEntryType.NOP);
        ByteBuf payload = Unpooled.buffer();
        Serializers.CORFU.serialize(entry, payload);
        WriteRequest wr = new WriteRequest(WriteMode.NORMAL, type,  null,  payload);
        wr.setStreams(streams);
        wr.setRank(rank);
        wr.setGlobalAddress(address);
        CompletableFuture<Boolean> cf = router.sendMessageAndGetCompletable(CorfuMsgType.WRITE.payloadMsg(wr));
        return cf.thenApply(x -> { context.stop(); return x; });
    }


    /**
     * Asynchronously write to the logging unit.
     * @param payload   The log data to write to the logging unit.
     * @return          A CompletableFuture which will complete with the WriteResult once the
     *                  write completes.
     */
    public CompletableFuture<Boolean> write(ILogData payload) {
        return router.sendMessageAndGetCompletable(CorfuMsgType.WRITE.payloadMsg(new WriteRequest(payload)));
    }

    /**
     * Asynchronously write to the logging unit.
     *
     * @param address        The address to write to.
     * @param streams        The streams, if any, that this write belongs to.
     * @param rank           The rank of this write (used for quorum
     *                       replication).
     * @param buffer         The object, post-serialization, to write.
     * @param backpointerMap The map of backpointers to write.
     * @return A CompletableFuture which will complete with the WriteResult once the
     * write completes.
     */
    public CompletableFuture<Boolean> write(long address, Set<UUID> streams, IMetadata.DataRank rank,
                                            ByteBuf buffer, Map<UUID, Long> backpointerMap) {
        Timer.Context context = getTimerContext("writeByteBuf");
        WriteRequest wr = new WriteRequest(WriteMode.NORMAL, null, buffer);
        wr.setStreams(streams);
        wr.setRank(rank);
        wr.setBackpointerMap(backpointerMap);
        wr.setGlobalAddress(address);
        CompletableFuture<Boolean> cf = router.sendMessageAndGetCompletable(CorfuMsgType.WRITE.payloadMsg(wr));
        return cf.thenApply(x -> { context.stop(); return x; });
    }

    public CompletableFuture<Boolean> writeStream(long address, Map<UUID, Long> streamAddresses,
                                                  Object object) {
        Timer.Context context = getTimerContext("writeStreamObject");
        ByteBuf payload = Unpooled.buffer();
        Serializers.CORFU.serialize(object, payload);
        CompletableFuture<Boolean> cf = writeStream(address, streamAddresses, payload);
        return cf.thenApply(x -> { context.stop(); return x; });
    }

    public CompletableFuture<Boolean> writeStream(long address, Map<UUID, Long> streamAddresses,
                                                  ByteBuf buffer) {
        Timer.Context context = getTimerContext("writeStreamByteBuf");
        WriteRequest wr = new WriteRequest(WriteMode.REPLEX_STREAM, streamAddresses, buffer);
        wr.setLogicalAddresses(streamAddresses);
        wr.setGlobalAddress(address);
        CompletableFuture<Boolean> cf = router.sendMessageAndGetCompletable(CorfuMsgType.WRITE.payloadMsg(wr));
        return cf.thenApply(x -> { context.stop(); return x; });
    }

    public CompletableFuture<Boolean> writeCommit(Map<UUID, Long> streams, long address, boolean commit) {
        Timer.Context context = getTimerContext("writeCommit");
        CommitRequest wr = new CommitRequest(streams, address, commit);
        CompletableFuture<Boolean> cf = router.sendMessageAndGetCompletable(CorfuMsgType.COMMIT.payloadMsg(wr));
        return cf.thenApply(x -> { context.stop(); return x; });
    }

    /**
     * Asynchronously read from the logging unit.
     *
     * @param address The address to read from.
     * @return A CompletableFuture which will complete with a ReadResult once the read
     * completes.
     */
    public CompletableFuture<ReadResponse> read(long address) {
        Timer.Context context = getTimerContext("read");
        CompletableFuture<ReadResponse> cf = router.sendMessageAndGetCompletable(
                CorfuMsgType.READ_REQUEST.payloadMsg(new ReadRequest(address)));

        return cf.thenApply(x -> { context.stop(); return x; });
    }

    public CompletableFuture<ReadResponse> read(UUID stream, Range<Long> offsetRange) {
        Timer.Context context = getTimerContext("readRange");
        CompletableFuture<ReadResponse> cf = router.sendMessageAndGetCompletable(
                CorfuMsgType.READ_REQUEST.payloadMsg(new ReadRequest(offsetRange, stream)));
        return cf.thenApply(x -> { context.stop(); return x; });
    }

    /**
     * Get the global tail maximum address the log unit has written.
     * @return A CompletableFuture which will complete with the globalTail once
     * received.
     */
    public CompletableFuture<Long> getTail() {
        return router.sendMessageAndGetCompletable(CorfuMsgType.TAIL_REQUEST.msg());
    }

    /**
     * Send a hint to the logging unit that a stream can be trimmed.
     *
     * @param stream The stream to trim.
     * @param prefix The prefix of the stream, as a global physical offset, to trim.
     */
    public void trim(UUID stream, long prefix) {
        router.sendMessage(CorfuMsgType.TRIM.payloadMsg(new TrimRequest(stream, prefix)));
    }

    /**
     * Fill a hole at a given address.
     *
     * @param address The address to fill a hole at.
     */
    public CompletableFuture<Boolean> fillHole(long address) {
        Timer.Context context = getTimerContext("fillHole");
        CompletableFuture<Boolean> cf = router.sendMessageAndGetCompletable(
                CorfuMsgType.FILL_HOLE.payloadMsg(new FillHoleRequest(null, address)));
        return cf.thenApply(x -> { context.stop(); return x; });
    }

    public CompletableFuture<Boolean> fillHole(UUID streamID, long address) {
        Timer.Context context = getTimerContext("fillHole");
        CompletableFuture<Boolean> cf = router.sendMessageAndGetCompletable(
                CorfuMsgType.FILL_HOLE.payloadMsg(new FillHoleRequest(streamID, address)));
        return cf.thenApply(x -> { context.stop(); return x; });
    }


    /**
     * Force the garbage collector to begin garbage collection.
     */
    public void forceGC() {
        router.sendMessage(CorfuMsgType.FORCE_GC.msg());
    }

    /**
     * Force the compactor to recalculate the contiguous tail.
     */
    public void forceCompact() {
        router.sendMessage(CorfuMsgType.FORCE_COMPACT.msg());
    }

    /**
     * Change the default garbage collection interval.
     *
     * @param millis The new garbage collection interval, in milliseconds.
     */
    public void setGCInterval(long millis) {
        router.sendMessage(CorfuMsgType.GC_INTERVAL.payloadMsg(millis));
    }

    private Timer.Context getTimerContext(String opName) {
        Timer t = CorfuRuntime.getMetrics().timer(
                CorfuRuntime.getMpLUC() +
                getHost() + ":" + getPort().toString() + "-" + opName);
        return t.time();
    }
}
