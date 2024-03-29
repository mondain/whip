package org.red5.whip;

import java.util.concurrent.CopyOnWriteArraySet;

import org.red5.server.BaseConnection;
import org.red5.server.api.IContext;
import org.red5.server.api.Red5;
import org.red5.server.api.scope.IBroadcastScope;
import org.red5.server.api.scope.IScope;
import org.red5.server.api.stream.IBroadcastStream;
import org.red5.server.stream.IProviderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.red5pro.media.sdp.SessionDescription;
import com.red5pro.override.ProStream;
import com.red5pro.server.ConnectionAttributeKey;
import com.red5pro.server.stream.webrtc.IRTCStream;
import com.red5pro.server.stream.webrtc.IRTCStreamSession;
import com.red5pro.webrtc.session.RTCStreamSession;

/**
 * Whip implementation of a session service.
 * 
 * @author Paul Gregoire
 */
public class WhipSessionService {

    private Logger log = LoggerFactory.getLogger(getClass());

    private static CopyOnWriteArraySet<IRTCStreamSession> sessions = new CopyOnWriteArraySet<>();

    public void start() {
    }

    public void stop() {
        sessions.forEach(session -> cleanupStreamSession(session));
        sessions.clear();
    }

    public WhipPublisher setupPublisher(WhipConnection conn, String streamName, SessionDescription offerSdp) throws Exception {
        log.debug("setupPublisher: {} connection: {}", streamName, conn);
        // set the thread local for internals etc
        Red5.setConnectionLocal(conn);
        WhipPublisher publisher = new WhipPublisher(conn, streamName);
        // creates / gets the broadcast stream
        ProStream proStream = (ProStream) publisher.getProStream();
        // set the offer
        publisher.setOffer(offerSdp);
        // build a stream session with an RTC source for tying rtc/rtmp stream together
        RTCStreamSession session = new RTCStreamSession(publisher, proStream);
        // XXX this isnt available in the webapp classloader due to this classes resolution
        // set the handler
        //session.setHandler(this);
        // add session
        sessions.add(session);
        // tells the session to start internal members
        session.start(conn.getUserAgentEnum());
        // publish name
        String publishName = proStream.getPublishedName();
        // register the publisher, but don't publish yet        
        IScope scope = conn.getScope();
        IContext context = scope.getContext();
        IProviderService providerService = (IProviderService) context.getBean(IProviderService.BEAN_NAME);
        if (providerService.registerBroadcastStream(scope, streamName, proStream)) {
            IBroadcastScope bsScope = scope.getBroadcastScope(streamName);
            bsScope.setClientBroadcastStream(proStream);
            ((BaseConnection) conn).registerBasicScope(bsScope);
            log.debug("Scope: {} registered with connection: {}", bsScope.getPath(), conn.getSessionId());
            // set the connection on the stream for later referencing
            proStream.setConnection(conn);
            // add stream name to the connection
            conn.setAttribute(ConnectionAttributeKey.STREAM_NAME, publishName);
            conn.setSession(session);
            log.debug("Successfully registered for publishing: {}", publishName);
        } else {
            log.info("Failed to register for publishing: {}", publishName);
            cleanupStreamSession(session);
        }
        // clear thread local
        Red5.setConnectionLocal(null);
        return publisher;
    }

    public IRTCStreamSession getStreamSessionForRequest(String requestId) {
        if (log.isDebugEnabled()) {
            log.debug("Get session for {}\n{}", requestId, sessions);
        }
        for (IRTCStreamSession session : sessions) {
            // look for matching request id
            IRTCStream rtcStream = (IRTCStream) session.getRtcStream();
            if (rtcStream instanceof WhipPublisher && ((WhipPublisher) rtcStream).getName().equals(requestId)) {
                log.debug("Session found for request: {}", session);
                return session;
            }
        }
        log.debug("Session not found for {}", requestId);
        return null;
    }

    public void cleanupStreamSession(String requestId) {
        if (log.isDebugEnabled()) {
            log.debug("Clean up session for {}\n{}", requestId, sessions);
        }
        sessions.forEach(session -> {
            // look for matching request id
            IRTCStream rtcStream = (IRTCStream) session.getRtcStream();
            if (rtcStream instanceof WhipPublisher && ((WhipPublisher) rtcStream).getName().equals(requestId)) {
                log.debug("Session found for request: {}", session);
                cleanupStreamSession(session);
            }
        });
    }

    public void cleanupStreamSession(IRTCStreamSession session) {
        if (sessions.remove(session)) {
            session.getRtcStream().stop();
            session.getProStream().stop();
        }
    }

}
