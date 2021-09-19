package org.red5.whip;

import java.io.BufferedReader;
import java.io.IOException;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.red5.server.api.scope.IGlobalScope;
import org.red5.server.api.scope.IScope;
import org.red5.server.plugin.PluginRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.red5pro.media.sdp.SDPFactory;
import com.red5pro.media.sdp.SessionDescription;
import com.red5pro.plugin.Red5ProPlugin;
import com.red5pro.util.ScopeUtil;
import com.red5pro.webrtc.plugin.WebRTCPlugin;

/**
 * This servlet provides ingest for WHIP.
 * 
 * @author Paul Gregoire
 */
public class WhipEndpoint extends HttpServlet {

    private static final long serialVersionUID = 3366655542L;

    private final Logger log = LoggerFactory.getLogger(getClass());

    private static final String CONTENT_TYPE = "application/sdp";

    private static WebRTCPlugin plugin;

    private static WhipSessionService sessionService;

    @Override
    public void init(ServletConfig config) throws ServletException {
        plugin = ((WebRTCPlugin) PluginRegistry.getPlugin(WebRTCPlugin.NAME));
        sessionService = new WhipSessionService();
        super.init(config);
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (log.isDebugEnabled()) {
            // dump the headers
            request.getHeaderNames().asIterator().forEachRemaining(headerName -> {
                log.debug("Header {}: {}", headerName, request.getHeader(headerName));
            });
            request.getParameterNames().asIterator().forEachRemaining(paramName -> {
                log.debug("Parameter {}: {}", paramName, request.getParameter(paramName));
            });
        }
        // ensure the server is ready
        if (Red5ProPlugin.isReady() && request.getContentLength() > 0) {
            log.info("Endpoint creation is ready");
            // do one-at-a-time to make sure we don't miss a plugin from init
            if (plugin == null) {
                plugin = ((WebRTCPlugin) PluginRegistry.getPlugin(WebRTCPlugin.NAME));
            }
            // get the stream id / name
            String streamId = request.getParameter("streamId");
            log.info("Stream id: {}", streamId);
            // offer sdp
            SessionDescription offer = null;
            // read the body
            try (BufferedReader br = request.getReader()) {
                int b;
                StringBuilder buf = new StringBuilder();
                while ((b = br.read()) != -1) {
                    buf.append((char) b);
                }
                br.close();
                offer = SDPFactory.createSessionDescription(buf.toString());
            } catch (IOException e) {
                log.warn("Exception reading the sdp offer for {}", streamId, e);
            }
            log.debug("SDP offer: {}", offer);
            if (offer != null) {
                // start at the global scope               
                IGlobalScope global = plugin.getServer().getGlobal("default");
                // applications scope
                IScope appScope = global.getScope("whip");
                if (appScope == null) {
                    // fallback to live
                    global.getScope("live");
                }
                log.debug("Application scope: {}", appScope);
                String requestedURI = request.getRequestURI(); // /whip/endpoint
                log.debug("Request URI: {}", requestedURI);
                String path = requestedURI.equals("/whip/endpoint") ? "" : requestedURI.replace("whip/endpoint", "");
                log.debug("Path: {}", path);
                // connect to the scope
                IScope scope = path.length() > 0 ? ScopeUtil.resolveScope(appScope, path, true, false) : appScope;
                if (scope != null) {
                    // http response sent already?
                    boolean responseSent = false;
                    // create a wrapper for this connection
                    WhipConnection conn = new WhipConnection(request.getHeader("user-agent"));
                    conn.setClientId(streamId);
                    // connect to the scope
                    conn.connect(scope);
                    // create a publisher rtc session
                    WhipPublisher publisher;
                    try {
                        publisher = sessionService.setupPublisher(conn, streamId, offer);
                        // get the answer sdp
                        String answer = publisher.getLocalSdp();
                        // as bytes
                        byte[] answerBytes = answer.toString().getBytes();
                        // generated location header for PATCH and DELETE POST's
                        String location = String.format("%s/endpoint?streamId=%s", scope.getContextPath(), streamId);
                        // prepare response
                        response.setHeader("X-Powered-By", "Red5");
                        response.setHeader("location", location);
                        response.setStatus(HttpServletResponse.SC_ACCEPTED);
                        response.setContentType(CONTENT_TYPE);
                        response.setCharacterEncoding("UTF-8");
                        response.setContentLength(answerBytes.length);
                        try {
                            response.getOutputStream().write(answerBytes);
                            responseSent = true;
                        } catch (Exception e) {
                            log.warn("Exception writing sdp answer", e);
                        }
                        // start the publish
                        if (publisher.start()) {
                            // SUCCESS!
                        } else {
                            conn.close("Publish start failed", true);
                        }
                    } catch (Exception e) {
                        conn.close("Publisher failed", true);
                        // error not allowed after response is sent
                        if (!responseSent) {
                            response.sendError(HttpServletResponse.SC_NOT_ACCEPTABLE, e.getMessage()); // 406
                        }
                        log.warn("Publish failed for {} at {}", streamId, requestedURI, e);
                    }
                } else {
                    log.warn("Scope resolver failed for {} at {}", streamId, requestedURI);
                    response.sendError(HttpServletResponse.SC_NOT_FOUND);
                }
            } else {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid offer"); // 400
            }
        } else {
            response.sendError(HttpServletResponse.SC_PRECONDITION_FAILED, "Service not ready or invalid request body"); // 412
        }
    }

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED, "GET method not supported"); // 405
    }

    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        // ensure the server is ready
        if (Red5ProPlugin.isReady()) {
            log.info("Endpoint deletion is ready");
            // do one-at-a-time to make sure we don't miss a plugin from init
            if (plugin == null) {
                plugin = ((WebRTCPlugin) PluginRegistry.getPlugin(WebRTCPlugin.NAME));
            }
            // start at the global scope               
            IGlobalScope global = plugin.getServer().getGlobal("default");
            // applications scope
            IScope appScope = global.getScope("whip");
            if (appScope == null) {
                // fallback to live
                global.getScope("live");
            }
            log.debug("Application scope: {}", appScope);
            String requestedURI = request.getRequestURI(); // /whip/endpoint
            log.debug("Request URI: {}", requestedURI);
            String path = requestedURI.equals("/whip/endpoint") ? "" : requestedURI.replace("whip/endpoint", "");
            log.debug("Path: {}", path);
            // connect to the scope
            IScope scope = path.length() > 0 ? ScopeUtil.resolveScope(appScope, path, false, false) : appScope;
            if (scope != null) {
                // get the stream id / name
                String streamId = request.getParameter("streamId");
                log.info("Stream id: {}", streamId);
                sessionService.cleanupStreamSession(streamId);
                if (sessionService.getStreamSessionForRequest(streamId) == null) {
                    response.setStatus(HttpServletResponse.SC_OK);
                } else {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND, "Session not found"); // 404
                }
            } else {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Invalid path"); // 404
            }
        } else {
            response.sendError(HttpServletResponse.SC_PRECONDITION_FAILED, "Service not ready"); // 412
        }
    }

}
