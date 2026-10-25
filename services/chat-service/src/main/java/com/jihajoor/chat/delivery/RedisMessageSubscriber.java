package com.jihajoor.chat.delivery;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import com.jihajoor.chat.api.dto.MessageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Each node's end of the Redis message channel ({@code jihajoor.chat.cluster=redis}): every
 * message any node published through {@link RedisMessageDeliveryAdapter} arrives here once and is
 * handed to this node's sockets and STOMP subscribers by {@link LocalMessageFanout}
 * (Requirement 18.2). The STOMP broker stays a per-node simple broker; this subscription is what
 * feeds it the other nodes' messages.
 *
 * <p><b>Order.</b> Redis delivers a channel's messages in publish order, but socket writes can
 * block on a slow peer and must not run on the Redis connection's I/O thread. Messages are
 * therefore handed to a fixed set of single-threaded lanes chosen by booking, which keeps one
 * conversation in order while a stalled peer on one booking cannot hold up the rest of the node's
 * bookings (only those sharing its lane). The listener container must call {@link #onMessage}
 * synchronously, in arrival order, for that to hold.
 */
public class RedisMessageSubscriber implements MessageListener, AutoCloseable {

    /** Lanes in production: enough to isolate slow peers, few enough to be free when idle. */
    static final int DEFAULT_LANES = 8;

    private static final Logger log = LoggerFactory.getLogger(RedisMessageSubscriber.class);

    private final LocalMessageFanout fanout;
    private final ObjectMapper objectMapper;
    private final List<? extends Executor> lanes;

    /**
     * @param lanes executors that each run their tasks one at a time, in submission order (tests
     *              pass {@code Runnable::run})
     */
    public RedisMessageSubscriber(LocalMessageFanout fanout, ObjectMapper objectMapper,
                                  List<? extends Executor> lanes) {
        if (lanes.isEmpty()) {
            throw new IllegalArgumentException("at least one delivery lane is required");
        }
        this.fanout = fanout;
        this.objectMapper = objectMapper;
        this.lanes = List.copyOf(lanes);
    }

    /** A subscriber with {@link #DEFAULT_LANES} lanes, each a single virtual thread. */
    public static RedisMessageSubscriber withVirtualThreadLanes(LocalMessageFanout fanout,
                                                                ObjectMapper objectMapper) {
        List<ExecutorService> lanes = new ArrayList<>(DEFAULT_LANES);
        for (int i = 0; i < DEFAULT_LANES; i++) {
            lanes.add(Executors.newSingleThreadExecutor(
                    Thread.ofVirtual().name("chat-fanout-" + i).factory()));
        }
        return new RedisMessageSubscriber(fanout, objectMapper, lanes);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String json = new String(message.getBody(), StandardCharsets.UTF_8);
        MessageResponse view;
        try {
            view = objectMapper.readValue(json, MessageResponse.class);
        } catch (JacksonException e) {
            log.warn("Dropped an unreadable chat message from Redis");
            return;
        }
        if (view == null || view.bookingId() == null) {
            log.warn("Dropped a chat message without a booking id from Redis");
            return;
        }
        Executor lane = lanes.get(Math.floorMod(view.bookingId().hashCode(), lanes.size()));
        try {
            lane.execute(() -> deliver(view, json));
        } catch (RejectedExecutionException e) {
            // Shutting down; the clients reload history when they reconnect.
            log.debug("Chat fan-out is stopped; message {} not delivered on this node", view.id());
        }
    }

    private void deliver(MessageResponse view, String json) {
        try {
            fanout.deliver(view, json);
        } catch (RuntimeException e) {
            log.warn("Could not fan out chat message {} of booking {} on this node", view.id(),
                    view.bookingId(), e);
        }
    }

    @Override
    public void close() {
        for (Executor lane : lanes) {
            if (lane instanceof ExecutorService service) {
                service.shutdown();
            }
        }
    }
}
