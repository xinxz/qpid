/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.qpid.server;

import org.apache.qpid.framing.MessageTransferBody;
import org.apache.qpid.framing.CompositeAMQDataBlock;
import org.apache.qpid.framing.AMQFrame;
import org.apache.qpid.AMQException;
import org.apache.qpid.server.queue.AMQMessage;

import java.util.List;

/**
 * Signals that a required delivery could not be made. This could be bacuse of
 * the immediate flag being set and the queue having no consumers, or the mandatory
 * flag being set and the exchange having no valid bindings.
 */
public abstract class RequiredDeliveryException extends AMQException
{
    private final String _message;
    private final AMQMessage _payload;

    public RequiredDeliveryException(String message, AMQMessage payload)
    {
        super(message);
        _message = message;
        _payload = payload;
    }

    /*    public CompositeAMQDataBlock getReturnMessage(int channel)
    {
	    // AMQP version change: All generated *Body classes are now version-aware.
        // Shortcut: hardwire version to 0-9 (major=0, minor=9) for now.
        // TODO: Connect the version to that returned by the ProtocolInitiation
        // for this session.
        BasicReturnBody returnBody = new BasicReturnBody((byte)0, (byte)9);
        returnBody.exchange = _transferBody.exchange;
        returnBody.replyCode = getReplyCode();
        returnBody.replyText = _message;
        returnBody.routingKey = _transferBody.routingKey;

        AMQFrame[] allFrames = new AMQFrame[2 + _contentBodies.size()];

        AMQFrame returnFrame = new AMQFrame();
        returnFrame.bodyFrame = returnBody;
        returnFrame.channel = channel;

        allFrames[0] = returnFrame;
        allFrames[1] = ContentHeaderBody.createAMQFrame(channel, _contentHeaderBody);
        for (int i = 2; i < allFrames.length; i++)
        {
            allFrames[i] = ContentBody.createAMQFrame(channel, _contentBodies.get(i - 2));
        }

        return new CompositeAMQDataBlock(allFrames);
        }*/

    public int getErrorCode()
    {
        return getReplyCode();
    }    

    public abstract int getReplyCode();
}
