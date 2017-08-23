package com.ndpmedia.flume.source.rocketmq;

import com.alibaba.rocketmq.client.consumer.MQPushConsumer;
import com.alibaba.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import com.alibaba.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import com.alibaba.rocketmq.client.consumer.listener.MessageListener;
import com.alibaba.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import com.alibaba.rocketmq.client.exception.MQClientException;
import com.alibaba.rocketmq.common.message.MessageExt;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.PollableSource;
import org.apache.flume.conf.Configurable;
import org.apache.flume.event.SimpleEvent;
import org.apache.flume.source.AbstractSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.apache.flume.source.PollableSourceConstants.*;

/**
 * RocketMQSource Created with rocketmq-flume.
 *
 * @author penuel (penuel.leo@gmail.com)
 * @date 15/9/17 下午12:13
 * @desc
 */
public class RocketMQSource extends AbstractSource implements Configurable, PollableSource {

    private static final Logger LOG = LoggerFactory.getLogger(RocketMQSource.class);

    private String topic;

    private String tag;

    private MQPushConsumer consumer;

    private MessageListener messageListener;

    private String extra;

    private int maxSize;//列表缓存最多的消息个数，大于maxSize则prcess event

    private long maxDelay;//消息缓存最长延迟时间，如果小于maxSize，但是大于maxDelay会立即发送所有消息

    private boolean asyn = false;//是否异步消费

    private long lastProcessTime = 0L;//上一次处理时间

    private RocketMQSourceCounter counter;

    private Long maxBackOffSleepInterval;

    private Long backoffSleepIncrement;

    private AtomicReference<List<Event>> events = new AtomicReference<List<Event>>();


    @Override public void configure(Context context) {
        topic = context.getString(RocketMQSourceConstant.TOPIC, RocketMQSourceConstant.DEFAULT_TOPIC);
        tag = context.getString(RocketMQSourceConstant.TAG, RocketMQSourceConstant.DEFAULT_TAG);
        extra = context.getString(RocketMQSourceConstant.EXTRA, null);
        asyn = context.getBoolean(RocketMQSourceConstant.ASYN, false);
        maxSize = context.getInteger(RocketMQSourceConstant.MAX_SIZE, 20);
        maxDelay = context.getLong(RocketMQSourceConstant.MAX_DELAY, 2000L);
        String messageModel = context.getString(RocketMQSourceConstant.MESSAGE_MODEL, RocketMQSourceConstant.DEFAULT_MESSAGE_MODEL);
        String fromWhere = context.getString(RocketMQSourceConstant.CONSUME_FROM_WHERE, RocketMQSourceConstant.DEFAULT_CONSUME_FROM_WHERE);
        backoffSleepIncrement = context.getLong(BACKOFF_SLEEP_INCREMENT, DEFAULT_BACKOFF_SLEEP_INCREMENT);
        maxBackOffSleepInterval = context.getLong(MAX_BACKOFF_SLEEP, DEFAULT_MAX_BACKOFF_SLEEP);

        messageListener = new CustomMessageListenerConcurrently();
        consumer = RocketMQSourceUtil.getConsumerInstance(context);

        if ( null == counter ){
            counter = new RocketMQSourceCounter(getName());
        }

        while ( !events.compareAndSet(null, new ArrayList<Event>()) ) {}

        try {
            consumer.subscribe(topic, tag);
            consumer.registerMessageListener(messageListener);
            if ( LOG.isInfoEnabled() ) {
                LOG.info("RocketMQSource configure success, topic={},tag={},messageModel={},fromWhere={},extra={}", topic, tag, messageModel, fromWhere, extra);
            }
        } catch ( MQClientException e ) {
            LOG.error("RocketMQSource configure fail", e);
        }
    }

    @Override public Status process() throws EventDeliveryException {
        try {
            if ( asyn ) {
                //TODO new thread process LinkedBlockingQueue
            }
            long currentTime = System.currentTimeMillis();
            while ( events.get().size() >= maxSize || (currentTime - lastProcessTime >= maxDelay && events.get().size() > 0) ) {
                long receivedTime = System.currentTimeMillis();
                counter.addToEventReceivedCount(events.get().size());
                counter.addToEventReceivedTimer((receivedTime-currentTime)/1000);

                getChannelProcessor().processEventBatch(events.getAndSet(new ArrayList<Event>()));

                long acceptedTime = System.currentTimeMillis();
                counter.addToEventAcceptedCount(events.get().size());
                counter.addToEventAcceptedTimer((acceptedTime-receivedTime)/1000);

                lastProcessTime = currentTime;
            }
        } catch ( Exception e ) {
            LOG.error("RocketMQSource process error", e);
            return Status.BACKOFF;
        }
        return Status.READY;
    }

    @Override public long getBackOffSleepIncrement() {
        return backoffSleepIncrement;
    }

    @Override public long getMaxBackOffSleepInterval() {
        return maxBackOffSleepInterval;
    }

    @Override public synchronized void start() {
        try {
            LOG.warn("RocketMQSource start consumer... ");
            consumer.start();
            counter.start();
        } catch ( MQClientException e ) {
            LOG.error("RocketMQSource start consumer failed", e);
        }
        super.start();
    }

    @Override public synchronized void stop() {
        // 停止Producer
        consumer.shutdown();
        counter.stop();
        LOG.warn("RocketMQSource stop consumer {}, Metrics:{} ", getName(), counter);
    }

    class CustomMessageListenerConcurrently implements MessageListenerConcurrently {

        @Override public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> messageExts,
                                                                  ConsumeConcurrentlyContext ctx) {
            if ( null == messageExts || messageExts.size() == 0 ) {
                LOG.error("consumeMessage() has null or empty list passed in");
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }

            int ackIndex = 0;
            for ( MessageExt messageExt : messageExts ) {
                Event event = new SimpleEvent();
                Map<String, String> headers = new HashMap<String, String>();
                headers.put(RocketMQSourceConstant.TOPIC, topic);
                headers.put(RocketMQSourceConstant.TAG, tag);
                headers.put(RocketMQSourceConstant.EXTRA, extra);
                headers.putAll(messageExt.getProperties());
                event.setHeaders(headers);
                event.setBody(messageExt.getBody());
                events.get().add(event);
                ctx.setAckIndex(++ackIndex);
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        }
    }
}
