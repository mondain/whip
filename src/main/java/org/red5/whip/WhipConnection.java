package org.red5.whip;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.mina.core.buffer.IoBuffer;
import org.ice4j.Transport;
import org.red5.server.api.IConnection;
import org.red5.server.api.Red5;
import org.red5.server.api.scope.IScope;
import org.red5.server.net.rtmp.RTMPConnection;
import org.red5.server.net.rtmp.codec.RTMP;
import org.red5.server.net.rtmp.message.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.red5pro.media.sdp.SDPUserAgent;
import com.red5pro.override.IProStream;
import com.red5pro.server.ConnectionAttributeKey;
import com.red5pro.server.SignalingChannel;
import com.red5pro.server.stream.webrtc.IRTCStreamSession;
import com.red5pro.webrtc.IRTCCapableConnection;
import com.red5pro.webrtc.plugin.WebRTCPlugin;
import com.red5pro.webrtc.session.SessionSourceTable;
import com.red5pro.webrtc.session.SessionSourceTableEventHandler;

public class WhipConnection extends RTMPConnection implements IRTCCapableConnection, SessionSourceTableEventHandler {

    private Logger log = LoggerFactory.getLogger(getClass());

    private boolean isDebug = log.isDebugEnabled();

    @SuppressWarnings("unused")
    private boolean isTrace = log.isTraceEnabled();

    private String userAgent;

    private SDPUserAgent userAgentEnum = SDPUserAgent.undefined;
    
    private String userAgentVersion;

    private Transport transport = WebRTCPlugin.getDefaultTransport();

    private long creationTime;

    private SessionSourceTable remotePartyTable;

    private IRTCStreamSession session;

    private AtomicBoolean closed = new AtomicBoolean(false);
    
    private String clientId;

    private long lastReceiveTime, lastSendTime;
    
    public WhipConnection(String userAgent) {
        super(IConnection.Type.PERSISTENT.name().toLowerCase());
        creationTime = System.currentTimeMillis();
        setStateCode(RTMP.STATE_CONNECT);
        setUserAgent(userAgent);
        remotePartyTable = new SessionSourceTable(this);
        // clear some items configured by super class
        decoderState = null;
    }

    @Override
    public boolean connect(IScope scope) {
        if (isDebug) {
            log.debug("connect {} {}", clientId, scope);
        }
        boolean res = super.connect(scope, new Object[] {});
        if (res) {
            setStateCode(RTMP.STATE_CONNECTED);
        } else {
            if (isDebug) {
                log.debug("connect failed {} {}", clientId, scope);
            }
            setStateCode(RTMP.STATE_DISCONNECTING);
        }
        return res;
    }

    @Override
    public void close() {
        log.debug("close - session id: {} client id: {}", sessionId, clientId);
        if (closed.compareAndSet(false, true)) {
            Red5.setConnectionLocal(this);
            setStateCode(RTMP.STATE_DISCONNECTING);
            if (session != null) {
                session.stop();
                session = null;
            }
            remotePartyTable.cancel();
            super.close();
            setStateCode(RTMP.STATE_DISCONNECTED);
            Red5.setConnectionLocal(null);
        }
    }

    @Override
    public boolean isIdle() {
        if (isConnected()) {
            long now = System.currentTimeMillis();
            if (now - creationTime < SessionSourceTable.GetMaxTimeout()) {
                log.debug("Connection is not yet idle due to not exceeding connection timeout process");
                return false;
            }
            // get party tables determination of idle
            return remotePartyTable.isIdle();
        }
        return false;
    }

    @Override
    protected void onInactive() {
        log.warn("onInactive fired, current state: {}", RTMP.states[getState().getState()]);
    }

    @Override
    public void sessionSourceTimeout(int ssrc) {
    }

    @Override
    public void sessionInputAbandoned() {
        close("Session abandoned", true);
    }

    @Override
    public Encoding getEncoding() {
        return Encoding.RAW;
    }

    @Override
    public String getProtocol() {
        return "whip";
    }

    /**
     * Sets the media layer transport protocol.
     *
     * @param transport
     */
    public void setTransport(Transport transport) {
        this.transport = transport;
    }

    /**
     * Returns the transport.
     *
     * @return transport
     */
    public Transport getTransport() {
        return transport;
    }

    public IRTCStreamSession getSession() {
        return session;
    }

    public void setSession(IRTCStreamSession session) {
        if (session != null) {
            this.session = session;
        }
    }
    
    public String getStreamPath() {
        if (session != null) {
            IProStream stream = session.getProStream();
            if (stream != null) {
                return String.format("%s/%s", stream.getScope().getContextPath(), stream.getBroadcastStreamPublishName());
            }
        }
        return "";
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    /**
     * Sets the User-Agent.
     *
     * @see <a href=
     *      "https://www.whatismybrowser.com/developers/tools/user-agent-parser/browse">UA
     *      Browser List</a>
     *
     * @param userAgent User-Agent from the WebSocket
     */
    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
        if (isDebug) {
            String browser = "", os = "";
            if (isAndroid()) {
                os = "Android";
            } else if (isIOS()) {
                os = "iOS";
            }
            if (isSafari()) {
                browser = "Safari";
            } else if (isChrome()) {
                browser = "Chrome";
            } else if (isFirefox()) {
                browser = "Firefox";
            }
            log.debug("UA: {} on {}", browser, os);
        }
    }

    public SDPUserAgent getUserAgentEnum() {
        if (userAgentEnum == SDPUserAgent.undefined) {
            // base the ua search on a single updated and currently best flow
            SDPUserAgent ua = SDPUserAgent.undefined;
            if (isAndroid()) {
                if (isChrome()) {
                    ua = SDPUserAgent.android_chrome;
                } else if (isFirefox()) {
                    ua = SDPUserAgent.android_firefox;
                }
            } else if (isIOS()) {
                if (isChrome()) {
                    // userAgent = SDPUserAgent.ios_chrome;
                    ua = SDPUserAgent.chrome;
                } else if (isFirefox()) {
                    ua = SDPUserAgent.firefox;
                } else if (isSafari()) {
                    ua = SDPUserAgent.safari;
                }
                if (isSafari()) {
                    ua = SDPUserAgent.safari;
                } else if (isChrome()) {
                    ua = SDPUserAgent.chrome;
                } else if (isFirefox()) {
                    ua = SDPUserAgent.firefox;
                }
            } else {
                if (isSafari()) {
                    ua = SDPUserAgent.safari;
                } else if (isFirefox()) {
                    ua = SDPUserAgent.firefox;
                } else {
                    ua = SDPUserAgent.chrome;
                }
            }
            // set local ua so we can skip all the processing on the next call
            userAgentEnum = ua;
        }
        return userAgentEnum;
    }

    /**
     * Returns the user-agents version string.
     *
     * @return userAgentVersion
     */
    public String getUAVersion() {
        if (userAgentVersion == null) {
            String regex = "";
            if (isEdge()) {
                // Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like
                // Gecko) Chrome/64.0.3282.140 Safari/537.36 Edge/17.17134
                regex = "Edge\\/([.0-9]*)";
            } else if (isSafari()) {
                // Mozilla/5.0 (iPhone; CPU iPhone OS 12_2 like Mac OS X) AppleWebKit/605.1.15
                // (KHTML, like Gecko) Version/12.1 Mobile/15E148 Safari/604.1
                // Mozilla/5.0 (Macintosh; Intel Mac OS X 10_14_5) AppleWebKit/605.1.15 (KHTML,
                // like Gecko) Version/12.1.1 Safari/605.1.15
                regex = "Safari\\/([.0-9]*)";
            } else if (isChrome()) {
                // Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like
                // Gecko) Chrome/74.0.3729.169 Safari/537.36
                regex = "Chrome\\/([.0-9]*)";
            } else if (isFirefox()) {
                // Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:69.0) Gecko/20100101 Firefox/69.0
                regex = "Firefox\\/([.0-9]*)";
            }
            Matcher matcher = Pattern.compile(regex).matcher(userAgent);
            if (matcher.find()) {
                userAgentVersion = matcher.group(1);
            }
            log.info("UserAgent version: {}", userAgentVersion);
        }
        return userAgentVersion;
    }

    /**
     * Returns true if the User-Agent is Android.
     *
     * @return true if Android and false otherwise
     */
    public boolean isAndroid() {
        return userAgent.contains("Android");
    }

    public boolean isIOS() {
        return userAgent.contains("Mozilla/5.0 (iP"); // (iPhone && (iPad etc
    }

    /**
     * Returns true if the User-Agent is Chrome.
     *
     * @see <a href="https://developer.chrome.com/multidevice/user-agent">UA
     *      Reference</a>
     *
     * @return true if Chrome and false otherwise
     */
    public boolean isChrome() {
        return (userAgent.contains(" Chrome/") || userAgent.contains(" Chromium/") || userAgent.contains(" CriOS/")) && !userAgent.contains("OPR/");
    }

    /**
     * Returns whether or not its a webview.
     * 
     * @see https://developer.chrome.com/multidevice/user-agent
     *
     * @return true if WebView and false otherwise
     */
    public boolean isWebView() {
        return userAgent.contains(" wv)");
    }

    /**
     * Returns true if the User-Agent is Firefox. (ex. Mozilla/5.0 (X11; Ubuntu;
     * Linux x86_64; rv:69.0) Gecko/20100101 Firefox/69.0)
     *
     * @see <a href=
     *      "https://developer.mozilla.org/en-US/docs/Web/HTTP/Gecko_user_agent_string_reference">UA
     *      Reference</a>
     *
     * @return true if Firefox and false otherwise
     */
    public boolean isFirefox() {
        return userAgent.contains(" Firefox/") || userAgent.contains(" Fennec/") || userAgent.contains(" FxiOS/") || userAgent.contains(" SeaMonkey/");
    }

    public boolean isEdge() {
        return userAgent.contains("Edge/") && userAgent.contains("(Win");
    }

    public boolean isSafari() {
        return userAgent.contains(" Safari/") && userAgent.contains(" Version/") && userAgent.contains("AppleWebKit") && !userAgent.contains(" Chrome/");
    }

    public boolean isConnected() {
        return state.getState() == RTMP.STATE_CONNECTED;
    }
    
    public boolean isClosed() {
        return closed.get();
    }
    
    @Override
    public void close(String closeMessage, boolean close) {
        close();
    }

    @Override
    public SignalingChannel getSignalChannel() {
        // no signal channel for whip
        return null;
    }

    @Override
    public SessionSourceTable getRemotePartyTable() {
        return remotePartyTable;
    }

    public void updateReceivePacketTime(long packetTime) {
        if (lastReceiveTime < packetTime) {
            lastReceiveTime = packetTime;
        }
    }

    public void updateSendPacketTime(long packetTime) {
        if (lastSendTime < packetTime) {
            lastSendTime = packetTime;
        }
    }

    @Override
    public void updateAudioRecvCounter() {        
    }

    @Override
    public void updateVideoRecvCounter() {
    }

    @Override
    public void updateOtherRecvCounter() {
    }

    @Override
    public void write(Packet out) {
        // no output writing att
    }

    @Override
    public void writeRaw(IoBuffer out) {
        // no output writing att
    }

    @Override
    public void writeMessage(String message) {
        // no signal channel for whip, ignore
    }

    @Override
    public void setAttribute(ConnectionAttributeKey key, Object value) {
        setAttribute(key.value, value);
    }

}
