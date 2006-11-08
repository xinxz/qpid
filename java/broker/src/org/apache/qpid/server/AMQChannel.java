/*
 *
 * Copyright (c) 2006 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.apache.qpid.server;

import org.apache.log4j.Logger;
import org.apache.qpid.AMQException;
import org.apache.qpid.framing.BasicPublishBody;
import org.apache.qpid.framing.ContentBody;
import org.apache.qpid.framing.ContentHeaderBody;
import org.apache.qpid.server.ack.UnacknowledgedMessage;
import org.apache.qpid.server.ack.UnacknowledgedMessageMap;
import org.apache.qpid.server.ack.UnacknowledgedMessageMapImpl;
import org.apache.qpid.server.exchange.MessageRouter;
import org.apache.qpid.server.protocol.AMQProtocolSession;
import org.apache.qpid.server.queue.AMQMessage;
import org.apache.qpid.server.queue.AMQQueue;
import org.apache.qpid.server.queue.MessageHandleFactory;
import org.apache.qpid.server.store.MessageStore;
import org.apache.qpid.server.txn.LocalTransactionalContext;
import org.apache.qpid.server.txn.TransactionalContext;
import org.apache.qpid.server.txn.TxnBuffer;
import org.apache.qpid.server.txn.NonTransactionalContext;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class AMQChannel
{
    public static final int DEFAULT_PREFETCH = 5000;

    private static final Logger _log = Logger.getLogger(AMQChannel.class);

    private final int _channelId;

    //private boolean _transactional;

    private long _prefetchCount;

    /**
     * The delivery tag is unique per channel. This is pre-incremented before putting into the deliver frame so that
     * value of this represents the <b>last</b> tag sent out
     */
    private AtomicLong _deliveryTag = new AtomicLong(0);

    /**
     * A channel has a default queue (the last declared) that is used when no queue name is
     * explictily set
     */
    private AMQQueue _defaultQueue;

    /**
     * This tag is unique per subscription to a queue. The server returns this in response to a
     * basic.consume request.
     */
    private int _consumerTag;

    /**
     * The current message - which may be partial in the sense that not all frames have been received yet -
     * which has been received by this channel. As the frames are received the message gets updated and once all
     * frames have been received the message can then be routed.
     */
    private AMQMessage _currentMessage;

    /**
     * Maps from consumer tag to queue instance. Allows us to unsubscribe from a queue.
     */
    private final Map<String, AMQQueue> _consumerTag2QueueMap = new TreeMap<String, AMQQueue>();

    private final MessageStore _messageStore;

    //private Map<Long, UnacknowledgedMessage> _unacknowledgedMessageMap = new LinkedHashMap<Long, UnacknowledgedMessage>(DEFAULT_PREFETCH);
    private UnacknowledgedMessageMap _unacknowledgedMessageMap = new UnacknowledgedMessageMapImpl(DEFAULT_PREFETCH);

    private final AtomicBoolean _suspended = new AtomicBoolean(false);

    private final MessageRouter _exchanges;

    private TransactionalContext _txnContext;

    private final List<RequiredDeliveryException> _returnMessages = new LinkedList<RequiredDeliveryException>();

    private MessageHandleFactory _messageHandleFactory = new MessageHandleFactory();

    public AMQChannel(int channelId, MessageStore messageStore, MessageRouter exchanges)
            throws AMQException
    {
        _channelId = channelId;
        _prefetchCount = DEFAULT_PREFETCH;
        _messageStore = messageStore;
        _exchanges = exchanges;
        // TODO: fix me to pass in the txn context or at least have a factory for it
        _txnContext = new NonTransactionalContext(_messageStore, this, _returnMessages);
    }

    /**
     * Sets this channel to be part of a local transaction
     */
    public void setLocalTransactional()
    {
        _txnContext = new LocalTransactionalContext(new TxnBuffer(_messageStore), _returnMessages);
    }

    public int getChannelId()
    {
        return _channelId;
    }

    public long getPrefetchCount()
    {
        return _prefetchCount;
    }

    public void setPrefetchCount(long prefetchCount)
    {
        _prefetchCount = prefetchCount;
    }

    public void setPublishFrame(BasicPublishBody publishBody, AMQProtocolSession publisher) throws AMQException
    {
        _currentMessage = new AMQMessage(_messageStore.getNewMessageId(), publishBody, _txnContext);
        // TODO: used in clustering only I think (RG)
        //_currentMessage.setPublisher(publisher);
    }

    public void publishContentHeader(ContentHeaderBody contentHeaderBody)
            throws AMQException
    {
        if (_currentMessage == null)
        {
            throw new AMQException("Received content header without previously receiving a BasicPublish frame");
        }
        else
        {
            _currentMessage.setContentHeaderBody(contentHeaderBody);
            routeCurrentMessage();
            _currentMessage.routingComplete(_messageStore, _messageHandleFactory);

            // check and deliver if header says body length is zero
            if (contentHeaderBody.bodySize == 0)
            {
                _currentMessage = null;
            }
        }
    }

    public void publishContentBody(ContentBody contentBody)
            throws AMQException
    {
        if (_currentMessage == null)
        {
            throw new AMQException("Received content body without previously receiving a JmsPublishBody");
        }

        // returns true iff the message was delivered (i.e. if all data was
        // received
        try
        {
            if (_currentMessage.addContentBodyFrame(contentBody))
            {
                _currentMessage = null;
            }
        }
        catch (AMQException e)
        {
            // we want to make sure we don't keep a reference to the message in the
            // event of an error
            _currentMessage = null;
            throw e;
        }
    }

    protected void routeCurrentMessage() throws AMQException
    {
        _exchanges.routeContent(_currentMessage);
    }

    /*protected void routeCurrentMessage2() throws AMQException
    {
        if (_transactional)
        {
            // don't create a transaction unless needed
            if (_currentMessage.isPersistent())
            {
                _txnBuffer.containsPersistentChanges();
            }

            //A publication will result in the enlisting of several
            //TxnOps. The first is an op that will store the message.
            //Following that (and ordering is important), an op will
            //be added for every queue onto which the message is
            //enqueued. Finally a cleanup op will be added to decrement
            //the reference associated with the routing.
            Store storeOp = new Store(_currentMessage);
            _txnBuffer.enlist(storeOp);
            _currentMessage.setTxnBuffer(_txnBuffer);
            try
            {
                _exchanges.routeContent(_currentMessage);
                _txnBuffer.enlist(new Cleanup(_currentMessage));
            }
            catch (RequiredDeliveryException e)
            {
                //Can only be due to the mandatory flag, as no attempt
                //has yet been made to deliver the message. The
                //message will thus not have been delivered to any
                //queue so we can return the message (without killing
                //the transaction) and for efficiency remove the store
                //operation from the buffer.
                _txnBuffer.cancel(storeOp);
                throw e;
            }
            finally
            {
                _currentMessage = null;
            }
        }
        else
        {
            try
            {
                _exchanges.routeContent(_currentMessage);
                //following check implements the functionality
                //required by the 'immediate' flag:
                _currentMessage.checkDeliveredToConsumer();
            }
            finally
            {
                _currentMessage.decrementReference();
                _currentMessage = null;
            }
        }
    } */

    public long getNextDeliveryTag()
    {
        return _deliveryTag.incrementAndGet();
    }

    public int getNextConsumerTag()
    {
        return ++_consumerTag;
    }

    /**
     * Subscribe to a queue. We register all subscriptions in the channel so that
     * if the channel is closed we can clean up all subscriptions, even if the
     * client does not explicitly unsubscribe from all queues.
     *
     * @param tag     the tag chosen by the client (if null, server will generate one)
     * @param queue   the queue to subscribe to
     * @param session the protocol session of the subscriber
     * @return the consumer tag. This is returned to the subscriber and used in
     *         subsequent unsubscribe requests
     * @throws ConsumerTagNotUniqueException if the tag is not unique
     * @throws AMQException                  if something goes wrong
     */
    public String subscribeToQueue(String tag, AMQQueue queue, AMQProtocolSession session, boolean acks)
            throws AMQException, ConsumerTagNotUniqueException
    {
        if (tag == null)
        {
            tag = "sgen_" + getNextConsumerTag();
        }
        if (_consumerTag2QueueMap.containsKey(tag))
        {
            throw new ConsumerTagNotUniqueException();
        }

        queue.registerProtocolSession(session, _channelId, tag, acks);
        _consumerTag2QueueMap.put(tag, queue);
        return tag;
    }


    public void unsubscribeConsumer(AMQProtocolSession session, String consumerTag) throws AMQException
    {
        AMQQueue q = _consumerTag2QueueMap.remove(consumerTag);
        if (q != null)
        {
            q.unregisterProtocolSession(session, _channelId, consumerTag);
        }
        else
        {
            throw new AMQException(_log, "Consumer tag " + consumerTag + " not known to channel " +
                                         _channelId);
        }
    }

    /**
     * Called from the protocol session to close this channel and clean up.
     *
     * @throws AMQException if there is an error during closure
     */
    public void close(AMQProtocolSession session) throws AMQException
    {
        /*if (_transactional)
        {
            synchronized (_txnBuffer)
            {
                _txnBuffer.rollback();//releases messages
            }
        } */
        _txnContext.rollback();
        unsubscribeAllConsumers(session);
        requeue();
    }

    private void unsubscribeAllConsumers(AMQProtocolSession session) throws AMQException
    {
        _log.info("Unsubscribing all consumers on channel " + toString());
        for (Map.Entry<String, AMQQueue> me : _consumerTag2QueueMap.entrySet())
        {
            me.getValue().unregisterProtocolSession(session, _channelId, me.getKey());
        }
        _consumerTag2QueueMap.clear();
    }

    /**
     * Add a message to the channel-based list of unacknowledged messages
     *
     * @param message the message that was delivered
     * @param deliveryTag the delivery tag used when delivering the message (see protocol spec for description of
     * the delivery tag)
     * @param queue the queue from which the message was delivered
     */
    public void addUnacknowledgedMessage(AMQMessage message, long deliveryTag, String consumerTag, AMQQueue queue)
    {
        _unacknowledgedMessageMap.add(deliveryTag, new UnacknowledgedMessage(queue, message, consumerTag, deliveryTag));
        checkSuspension();
    }

    /**
     * Called to attempt re-enqueue all outstanding unacknowledged messages on the channel.
     * May result in delivery to this same channel or to other subscribers.
     * @throws org.apache.qpid.AMQException if the requeue fails
     */
    public void requeue() throws AMQException
    {
        // we must create a new map since all the messages will get a new delivery tag when they are redelivered
        Collection<UnacknowledgedMessage> messagesToBeDelivered = _unacknowledgedMessageMap.cancelAllMessages();

        for (UnacknowledgedMessage unacked : messagesToBeDelivered)
        {
            if (unacked.queue != null)
            {
                _txnContext.deliver(unacked.message, unacked.queue);
            }
        }
    }

    /**
     * Called to resend all outstanding unacknowledged messages to this same channel.
     */
    public void resend(final AMQProtocolSession session) throws AMQException
    {
        _unacknowledgedMessageMap.visit(new UnacknowledgedMessageMap.Visitor()
        {
            public boolean callback(UnacknowledgedMessage message) throws AMQException
            {
                long deliveryTag = message.deliveryTag;
                String consumerTag = message.consumerTag;
                AMQMessage msg = message.message;

                msg.writeDeliver(session, _channelId, deliveryTag, consumerTag);
                // false means continue processing
                return false;
            }

            public void visitComplete()
            {
            }
        });
    }

    /**
     * Callback indicating that a queue has been deleted. We must update the structure of unacknowledged
     * messages to remove the queue reference and also decrement any message reference counts, without
     * actually removing the item since we may get an ack for a delivery tag that was generated from the
     * deleted queue.
     *
     * @param queue the queue that has been deleted
     * @throws org.apache.qpid.AMQException if there is an error processing the unacked messages
     */
    public void queueDeleted(final AMQQueue queue) throws AMQException
    {
        _unacknowledgedMessageMap.visit(new UnacknowledgedMessageMap.Visitor()
        {
            public boolean callback(UnacknowledgedMessage message) throws AMQException
            {
                if (message.queue == queue)
                {
                    message.queue = null;
                    try
                    {
                        message.message.decrementReference();
                    }
                    catch (AMQException e)
                    {
                        _log.error("Error decrementing ref count on message " + message.message.getMessageId() + ": " +
                                   e, e);
                    }
                }
                return false;
            }

            public void visitComplete()
            {
            }
        });
    }

    /**
     * Acknowledge one or more messages.
     *
     * @param deliveryTag the last delivery tag
     * @param multiple    if true will acknowledge all messages up to an including the delivery tag. if false only
     *                    acknowledges the single message specified by the delivery tag
     * @throws AMQException if the delivery tag is unknown (e.g. not outstanding) on this channel
     */
    public void acknowledgeMessage(long deliveryTag, boolean multiple) throws AMQException
    {
        _unacknowledgedMessageMap.acknowledgeMessage(deliveryTag, multiple, _txnContext);
        checkSuspension();
        /*if (_transactional)
        {
            //check that the tag exists to give early failure
            if (!multiple || deliveryTag > 0)
            {
                checkAck(deliveryTag);
            }
            //we use a single txn op for all acks and update this op
            //as new acks come in. If this is the first ack in the txn
            //we will need to create and enlist the op.
            if (_ackOp == null)
            {
                _ackOp = new TxAck(new AckMap());
                _txnBuffer.enlist(_ackOp);
            }
            //update the op to include this ack request
            if(multiple && deliveryTag == 0)
            {
                synchronized(_unacknowledgedMessageMapLock)
                {
                    //if have signalled to ack all, that refers only
                    //to all at this time
                    _ackOp.update(_lastDeliveryTag, multiple);
                }
            }
            else
            {
                _ackOp.update(deliveryTag, multiple);
            }
        }
        else
        {
            handleAcknowledgement(deliveryTag, multiple);
        } */
    }

    /**
     * Used only for testing purposes.
     *
     * @return the map of unacknowledged messages
     */
    public UnacknowledgedMessageMap getUnacknowledgedMessageMap()
    {
        return _unacknowledgedMessageMap;
    }

    private void checkSuspension()
    {
        boolean suspend = _unacknowledgedMessageMap.size() >= _prefetchCount;
        setSuspended(suspend);
    }

    public void setSuspended(boolean suspended)
    {
        boolean wasSuspended = _suspended.getAndSet(suspended);
        if (wasSuspended != suspended)
        {
            if (wasSuspended)
            {
                _log.debug("Unsuspending channel " + this);
                //may need to deliver queued messages
                for (AMQQueue q : _consumerTag2QueueMap.values())
                {
                    q.deliverAsync();
                }
            }
            else
            {
                _log.debug("Suspending channel " + this);
            }
        }
    }

    public boolean isSuspended()
    {
        return _suspended.get();
    }

    public void commit() throws AMQException
    {
        _txnContext.commit();
    }

    public void rollback() throws AMQException
    {
        _txnContext.rollback();
    }

    public String toString()
    {
        StringBuilder sb = new StringBuilder(30);
        sb.append("Channel: id ").append(_channelId).append(", transaction context: ").append(_txnContext);
        sb.append(", prefetch count: ").append(_prefetchCount);
        return sb.toString();
    }

    public ObjectName getObjectName()
            throws MalformedObjectNameException
    {
        StringBuilder sb = new StringBuilder(30);
        sb.append("Channel:id=").append(_channelId);
        return new ObjectName(sb.toString());
    }

    public void setDefaultQueue(AMQQueue queue)
    {
        _defaultQueue = queue;
    }

    public AMQQueue getDefaultQueue()
    {
        return _defaultQueue;
    }

    public void processReturns(AMQProtocolSession session)
    {
        for (RequiredDeliveryException bouncedMessage : _returnMessages)
        {
            AMQMessage message = bouncedMessage.getAMQMessage();
            message.writeReturn(session, _channelId, bouncedMessage.getReplyCode(), bouncedMessage.getMessage());
        }
        _returnMessages.clear();
    }
}
