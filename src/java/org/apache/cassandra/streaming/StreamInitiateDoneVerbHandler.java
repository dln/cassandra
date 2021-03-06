package org.apache.cassandra.streaming;

import org.apache.log4j.Logger;

import org.apache.cassandra.net.IVerbHandler;
import org.apache.cassandra.net.Message;
import org.apache.cassandra.streaming.StreamOutManager;

public class StreamInitiateDoneVerbHandler implements IVerbHandler
{
    private static Logger logger = Logger.getLogger(StreamInitiateDoneVerbHandler.class);

    public void doVerb(Message message)
    {
        if (logger.isDebugEnabled())
          logger.debug("Received a stream initiate done message ...");
        StreamOutManager.get(message.getFrom()).startNext();
    }
}
