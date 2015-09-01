/*
 * LibJitsi-Protocol
 *
 * Copyright @ 2015 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.xmpp.component;

import net.java.sip.communicator.impl.protocol.jabber.extensions.keepalive.*;
import net.java.sip.communicator.util.*;
import org.jitsi.service.configuration.*;
import org.jitsi.xmpp.util.*;
import org.jivesoftware.smack.provider.*;
import org.xmpp.component.*;
import org.xmpp.packet.*;

import java.util.*;

/**
 * Base class for XMPP components.
 *
 * @author Pawel Domas
 */
public abstract class ComponentBase
    extends AbstractComponent
{
    /**
     * The logger.
     */
    private final static Logger logger = Logger.getLogger(ComponentBase.class);

    /**
     * The name of the property which configures ping interval in ms. -1 to
     * disable pings.
     */
    private final static String PING_INTERVAL_PNAME = "PING_INTERVAL";

    /**
     * The name of the property used to configure ping timeout in ms.
     */
    private final static String PING_TIMEOUT_PNAME = "PING_TIMEOUT";

    /**
     * The name of the property which configures {@link #pingThreshold}.
     */
    private final static String PING_THRESHOLD_PNAME = "PING_THRESHOLD";

    /**
     * How often pings will be sent. -1 disables ping checks.
     */
    private final long pingInterval;

    /**
     * How long are we going to wait for ping response in ms.
     */
    private final long pingTimeout;

    /**
     * FIXME find best value, possibly 1 or 2, but using 3 for the start
     * After how many failures we will consider the connection broken.
     */
    private final int pingThreshold;

    /**
     * Map holding ping timeout tasks...
     * FIXME there is similar code in Whack, but no easy way to use it as we can
     * not access {@link org.jivesoftware.whack.ExternalComponent} from here.
     */
    private final Map<String, TimeoutTask> timeouts
        = new HashMap<String, TimeoutTask>();

    /**
     * How many times the ping has failed so far ?
     */
    private int pingFailures = 0;

    /**
     * Time used to schedule ping and timeout tasks.
     */
    private Timer pingTimer;

    /**
     * Creates new instance.
     * @param config <tt>ConfigurationService</tt> instance.
     * @param configPropertiesBase config properties base string used to
     *        construct full names. To this string dot + config property name
     *        will be added. Example: "org.jitsi.jicofo" + "." + PING_INTERVAL
     */
    public ComponentBase(ConfigurationService config,
                         String               configPropertiesBase)
    {
        configPropertiesBase += ".";

        pingInterval = config.getLong(
            configPropertiesBase + PING_INTERVAL_PNAME, 10000L);

        pingTimeout = config.getLong(
            configPropertiesBase + PING_TIMEOUT_PNAME, 5000L);

        pingThreshold = config.getInt(
            configPropertiesBase + PING_THRESHOLD_PNAME, 3);

        logger.info("Component " + configPropertiesBase  + " config: ");
        logger.info("  ping interval: " + pingInterval + " ms");
        logger.info("  ping timeout: " + pingTimeout + " ms");
        logger.info("  ping threshold: " + pingThreshold);
    }

    /**
     * Starts the components.
     */
    @Override
    public void start()
    {
        super.start();

        ProviderManager.getInstance().addIQProvider(
            "ping", "urn:xmpp:ping",
            new KeepAliveEventProvider());

        if (pingInterval > 0)
        {
            pingTimer = new Timer();
            pingTimer.schedule(new PingTask(), pingInterval, pingInterval);
        }
    }

    /**
     * Called before component's request queue is cleared.
     */
    @Override
    public void preComponentShutdown()
    {
        super.preComponentShutdown();

        if (pingTimer != null)
        {
            pingTimer.cancel();
            pingTimer = null;
        }
    }

    /**
     * Returns <tt>true</tt> if component's connection to XMPP server is
     * considered alive.
     */
    public boolean isConnectionAlive()
    {
        synchronized (timeouts)
        {
            return pingFailures < pingThreshold;
        }
    }

    @Override
    protected void handleIQResult(IQ iq)
    {
        super.handleIQResult(iq);
        synchronized (timeouts)
        {
            String packetId = iq.getID();
            TimeoutTask timeout = timeouts.get(packetId);
            if (timeout != null)
            {
                timeout.gotResult();
                timeouts.remove(packetId);

                pingFailures = 0;
            }
        }
    }

    /**
     * Tasks sends ping to the server and schedules timeout task.
     */
    private class PingTask extends TimerTask
    {
        @Override
        public void run()
        {
            try
            {
                String domain = getDomain();

                domain = domain.substring(domain.indexOf(".") + 1);

                KeepAliveEvent ping = new KeepAliveEvent(null, domain);

                IQ pingIq = IQUtils.convert(ping);

                logger.debug("Sending ping IQ: " + ping.toXML());

                send(pingIq);

                synchronized (timeouts)
                {
                    String packetId = pingIq.getID();
                    TimeoutTask timeout = new TimeoutTask(packetId);

                    timeouts.put(packetId, timeout);

                    pingTimer.schedule(timeout, pingTimeout);
                }
            }
            catch (Exception e)
            {
                logger.error("Failed to send ping", e);

                pingFailures++;
            }
        }
    }

    /**
     * FIXME: make generic so that components can use it to track timeouts
     * like it's done in {@link org.jivesoftware.whack.ExternalComponent}.
     *
     * Timeout task for ping packets.
     */
    private class TimeoutTask extends TimerTask
    {
        private final String packetId;

        private boolean hasResult;

        public TimeoutTask(String packetId)
        {
            this.packetId = packetId;
        }

        public void gotResult()
        {
            this.hasResult = true;

            logger.debug("Got ping response for: " + packetId);
        }

        @Override
        public void run()
        {
            if (!hasResult)
            {
                pingFailures++;

                logger.error("Ping timeout for ID: " + packetId);
            }
        }
    }
}
