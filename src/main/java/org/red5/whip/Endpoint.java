package org.red5.whip;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.http.HttpHeaders;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.red5.server.plugin.PluginRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.red5pro.interstitial.api.IInterstitialRequestHandler;
import com.red5pro.interstitial.api.InterstitialException;
import com.red5pro.interstitial.api.InterstitialRequest;
import com.red5pro.media.sdp.SDPFactory;
import com.red5pro.media.sdp.SessionDescription;
import com.red5pro.plugin.Red5ProPlugin;
import com.red5pro.webrtc.plugin.WebRTCPlugin;

/**
 * This servlet provides ingest for WHIP.
 * 
 * <br>
 * <ul>Actions:
 * <li>create</li>
 * <li>kill</li>
 * <li>list (default)</li>
 * </ul>
 * <br>
 * See the Readme.md for examples and additional information.
 * 
 * @author Paul Gregoire
 */
public class Endpoint extends HttpServlet {

    private static final long serialVersionUID = 3366655542L;

    private final Logger log = LoggerFactory.getLogger(getClass());
    
    private static final String CONTENT_TYPE = "application/sdp";

    private static WebRTCPlugin plugin;

    @Override
    public void init(ServletConfig config) throws ServletException {
        plugin = ((WebRTCPlugin) PluginRegistry.getPlugin(WebRTCPlugin.NAME));
        super.init(config);
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (log.isDebugEnabled()) {
            // dump the headers
            request.getHeaderNames().asIterator().forEachRemaining(headerName -> {
                log.debug("{}: {}", headerName, request.getHeader(headerName));
            });
        }
        // ensure the server is ready
        if (Red5ProPlugin.isReady() && request.getContentLength() > 0) {
            // do one-at-a-time to make sure we don't miss a plugin from init
            if (plugin == null) {
                plugin = ((WebRTCPlugin) PluginRegistry.getPlugin(WebRTCPlugin.NAME));
            }
            // get the stream id / name
            String streamId = request.getParameter("streamId");
            log.debug("Stream id: {}", streamId);
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
                log.debug("SDP: {}", offer);
            } catch (IOException e) {
                log.warn("Exception reading the sdp offer for {}", streamId, e);
            }
            /*
            try {
                int length = request.getContentLength();
                int read = 0;
                int hresult = 0;
                char[] buf = new char[length];
                BufferedReader br = new BufferedReader(new InputStreamReader(request.getInputStream()));
                while ((hresult = br.read(buf, read, length - read)) > 0) {
                    read += hresult;
                }

                String jsonString = new String(buf);
                log.trace("Incoming JSON: {}", jsonString);
                InterstitialRequest jsonRequest = gson.fromJson(jsonString, InterstitialRequest.class);

                if (StringUtils.isEmpty(jsonRequest.user)) {
                    // error
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
                    log.warn("No user specified");
                }
                if (StringUtils.isEmpty(jsonRequest.digest)) {
                    // error
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
                    log.warn("No digest specified");
                }
                if (!StringUtils.isEmpty(jsonRequest.resume)) {
                    for (IInterstitialRequestHandler handler : IInterstitialRequestHandler.handlers) {
                        log.info("resuming {}", jsonRequest.resume);
                        handler.resume(jsonRequest.user, jsonRequest.digest, jsonRequest.resume);
                    }
                } else if ((jsonRequest.inserts != null && !jsonRequest.inserts.isEmpty())) {
                    for (IInterstitialRequestHandler handler : IInterstitialRequestHandler.handlers) {
                        handler.newRequest(jsonRequest.user, jsonRequest.digest, jsonRequest.inserts);
                    }
                } else {
                    // error
                    response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "No inserts or no resume specified");
                    log.warn("No inserts or no resume specified");
                }
            } catch (InterstitialException ie) {
                // error
                try {
                    response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "InterstitialException: " + ie.getMessage());
                    log.warn("unexpected", ie);
                } catch (IOException ioe) {
                    log.warn("very unexpected", ioe);
                }
            } catch (IOException e) {
                // error
                try {
                    response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Unexpected exception");
                    log.warn("unexpected", e);
                } catch (IOException ioe) {
                    log.warn("very unexpected", ioe);
                }
            }
            */
            // the sdp answer
            SessionDescription answer = new SessionDescription();
            // as bytes
            byte[] answerBytes = answer.toString().getBytes();
            // prepare response
            response.setStatus(HttpServletResponse.SC_ACCEPTED);
            response.setContentType(CONTENT_TYPE);
            response.setCharacterEncoding("UTF-8");
            response.setContentLength(answerBytes.length);
            try {
                response.getOutputStream().write(answerBytes);
            } catch (Exception e) {
                log.warn("Exception writing sdp answer", e);
            }
        } else {
            response.sendError(HttpServletResponse.SC_PRECONDITION_FAILED, "Service not ready or invalid request body");
        }
    }

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED, "GET method not supported");
    }

}
