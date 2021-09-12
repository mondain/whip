package org.red5.whip;

import java.io.IOException;
import java.net.BindException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.media.format.AudioFormat;
import javax.media.format.ParameterizedVideoFormat;
import javax.media.format.VideoFormat;

import org.apache.mina.core.session.IoSession;
import org.ice4j.Transport;
import org.ice4j.TransportAddress;
import org.ice4j.ice.Agent;
import org.ice4j.ice.CandidateExtendedType;
import org.ice4j.ice.CandidatePair;
import org.ice4j.ice.CandidateTcpType;
import org.ice4j.ice.CandidateType;
import org.ice4j.ice.Component;
import org.ice4j.ice.HostCandidate;
import org.ice4j.ice.IceMediaStream;
import org.ice4j.ice.IceProcessingState;
import org.ice4j.ice.LocalCandidate;
import org.ice4j.ice.PeerReflexiveCandidate;
import org.ice4j.ice.RemoteCandidate;
import org.ice4j.ice.ServerReflexiveCandidate;
import org.ice4j.socket.IceSocketWrapper;
import org.jitsi.impl.neomedia.AudioMediaStreamImpl;
import org.jitsi.impl.neomedia.MediaStreamImpl;
import org.jitsi.impl.neomedia.VideoMediaStreamImpl;
import org.jitsi.impl.neomedia.format.MediaFormatImpl;
import org.jitsi.service.neomedia.MediaDirection;
import org.jitsi.service.neomedia.MediaStreamTarget;
import org.jitsi.service.neomedia.format.MediaFormat;
import org.red5.codec.AACAudio;
import org.red5.codec.AVCVideo;
import org.red5.codec.StreamCodecInfo;
import org.red5.server.api.IContext;
import org.red5.server.api.Red5;
import org.red5.server.api.IConnection.Duty;
import org.red5.server.api.scope.IScope;
import org.red5.server.stream.IProviderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.red5pro.io.NIOStreamConnector;
import com.red5pro.io.StreamConnector;
import com.red5pro.io.rtp.transform.dtls.DtlsControl;
import com.red5pro.io.rtp.transform.dtls.DtlsControl.Setup;
import com.red5pro.io.rtp.transform.dtls.DtlsControlImpl;
import com.red5pro.jmfext.media.protocol.rtmp.RTMPAudioOutputDevice;
import com.red5pro.jmfext.media.protocol.rtmp.RTMPVideoOutputDevice;
import com.red5pro.media.AudioConstants;
import com.red5pro.media.DataMediaStreamImpl;
import com.red5pro.media.SourceType;
import com.red5pro.media.VideoConstants;
import com.red5pro.media.rtp.RTPCodec;
import com.red5pro.media.rtp.RTPCodecEnum;
import com.red5pro.media.sdp.SDPUserAgent;
import com.red5pro.media.sdp.SessionDescription;
import com.red5pro.media.sdp.model.AttributeField;
import com.red5pro.media.sdp.model.AttributeKey;
import com.red5pro.media.sdp.model.BandwidthField;
import com.red5pro.media.sdp.model.ConnectionField;
import com.red5pro.media.sdp.model.MediaField;
import com.red5pro.media.sdp.model.OriginField;
import com.red5pro.media.sdp.model.SDPMediaType;
import com.red5pro.override.IProStream;
import com.red5pro.override.ProStream;
import com.red5pro.server.stream.webrtc.IRTCStream;
import com.red5pro.server.util.NetworkManager;
import com.red5pro.server.util.PortManager;
import com.red5pro.util.IdGenerator;
import com.red5pro.webrtc.plugin.WebRTCPlugin;
import com.red5pro.webrtc.stream.MuxMaster;
import com.red5pro.webrtc.util.CandidateParser;

/**
 * Represents Whip stream publisher.
 *
 * <ul>
 * Steps:
 * <li>setOffer</li>
 * <li>init</li>
 * <li>setupDTLS</li>
 * <li>setupICE</li>
 * <li>createAnswer</li>
 * <li>setupSockets</li>
 * <li>start</li>
 * </ul>
 *
 * @author Paul Gregoire
 */
public class WhipPublisher implements IRTCStream {

    private Logger log = LoggerFactory.getLogger(getClass());

    private boolean isTrace = log.isTraceEnabled();

    private boolean isDebug = log.isDebugEnabled();

    private IScope scope;

    // Connection associated with this stream
    private final WhipConnection conn;

    // Top-level stream associated with this publisher
    private ProStream proStream;

    // holder of bundled media streams
    private WhipMediaStreamBundle mediaStream;

    // name for the source stream
    private String sourceStreamName;

    // Local SDP offer
    private SessionDescription localSdp;

    // DTLS control style: active, passive, or actpass (default)
    private Setup dtlsControlSetup = Setup.ACTPASS;

    // ICE agent
    private volatile Agent agent;

    private CountDownLatch iceSetupLatch;

    private String publicIPAddress = NetworkManager.getPublicAddress();

    private String localIPAddress = NetworkManager.getLocalAddress();

    // port allocated and paired by this stream instance
    private int allocatedPort;

    // the selected audio codec
    private RTPCodecEnum selectedAudioCodec = RTPCodecEnum.OPUS;

    // the selected video codec
    private RTPCodecEnum selectedVideoCodec = RTPCodecEnum.H264_PMODE1;

    // the offer sdp
    private SessionDescription offerSdp;

    // the answer sdp
    private String answerSdp;

    private int audioBR = 128;

    private int videoBR = 500;

    // target frames per second; defaults to 25
    private int targetFPS = 25;

    private int audioPayloadType = -1;

    private int videoPayloadType = -1;

    private static final Map<String, String> EMPTY_MAP = Collections.emptyMap();

    private MuxMaster muxer;

    private int requestedAudioBitrate, requestedVideoBitrate;

    private int maximumMessageSize = WebRTCPlugin.getMaxMessageSize();

    // holder of dtls/srtp controls (audio, video, bundle, etc...)
    private Map<String, DtlsControl> controls = new HashMap<>();

    // set only if start() completes successfully
    private volatile boolean started;

    private AtomicBoolean starting = new AtomicBoolean();

    private AtomicBoolean stopped = new AtomicBoolean();

    // stream connector established upon successful ICE establishment
    private StreamConnector streamConnector;

    /**
     * Whip / WebRTC publisher stream.
     *
     * @param conn connection with the scope to broadcast on
     * @param streamName
     */
    public WhipPublisher(WhipConnection conn, String streamName) {
        this.conn = conn;
        this.scope = conn.getScope();
        this.sourceStreamName = streamName;
        // instance an agent
        agent = new Agent();
    }

    /**
     * Parse the offer sdp information and initialize the streams.
     */
    public void init(SDPUserAgent userAgent) throws Exception {
        log.debug("init id: {} ua: {}", sourceStreamName, userAgent);
        boolean offeredDataChannel = false;
        // create local sdp, the answer
        localSdp = new SessionDescription();
        // set ua
        localSdp.setUA(userAgent);
        // check for bandwidth session level first
        BandwidthField bandwidth = offerSdp.getBandwidth();
        if (bandwidth != null) {
            videoBR = bandwidth.getBandwidth();
            log.debug("offer bandwidth {}", videoBR);
        }
        // get encoding names in precedence order
        String[] audioEncNames = RTPCodecEnum.getAudioEncodingNamesAsArray();
        String[] videoEncNames = RTPCodecEnum.getVideoEncodingNamesAsArray();
        // sctp parameters
        String[] offeredSctp = null;
        // get all the media descriptions
        MediaField[] medias = offerSdp.getMediaDescriptions();
        if (medias == null) {
            throw new Exception("No media fields offered");
        }
        // create a muxer
        muxer = new MuxMaster(proStream);
        for (MediaField media : medias) {
            SDPMediaType mediaType = media.getMediaType();
            if (isTrace) {
                log.trace("Checking {}", media);
            }
            if (SDPMediaType.audio.equals(mediaType)) {
                // look over all incoming rtpmap for our server preferred codec(s)
                String audioCodec = media.getAttribute(AttributeKey.rtpmap, EnumSet.of(RTPCodecEnum.OPUS)).getValue();
                String[] audioCodecParts = audioCodec.split("[\\s|\\/]");
                audioPayloadType = Integer.valueOf(audioCodecParts[0]);
                String audioCodecName = audioCodecParts[1];
                log.debug("Offerred audio codec: {} {} {}", audioCodec, audioPayloadType, audioCodecName);
                for (String encName : audioEncNames) {
                    if (encName.equalsIgnoreCase(audioCodecName)) {
                        selectedAudioCodec = RTPCodecEnum.getByEncodingName(encName);
                        // add a media field to our local sdp
                        MediaField audio = new MediaField(mediaType, 9, MediaField.PROTOCOL_UDP, 1);
                        localSdp.addMediaDescription(audio);
                        log.debug("Audio streams in local sdp: {}", localSdp.getAudio());
                        break;
                    }
                }
                // check media level bandwidth
                bandwidth = media.getBandwidth();
                if (bandwidth != null) {
                    audioBR = bandwidth.getBandwidth();
                    requestedAudioBitrate = audioBR * 1000;
                } else {
                    requestedAudioBitrate = audioBR * 1000;
                }
                log.debug("Audio streams bitrate: {}", requestedAudioBitrate);
            } else if (SDPMediaType.video.equals(mediaType)) { // detect video type
                // look over all incoming rtpmap for our server preferred codec(s)
                List<AttributeField> videoCodecs = media.getAttributeSelections(AttributeKey.rtpmap, EnumSet.of(RTPCodecEnum.H264_PMODE1, RTPCodecEnum.VP8));
                String videoCodec = null;
                for (AttributeField videoAtt : videoCodecs) {
                    videoCodec = videoAtt.getValue();
                    String[] videoCodecParts = videoCodec.split("[\\s|\\/]");
                    int pt = Integer.valueOf(videoCodecParts[0]);
                    String videoCodecName = videoCodecParts[1];
                    log.debug("Offerred video codec: {} {} {}", videoCodec, pt, videoCodecName);
                    String offeredFmtp = media.getAttribute(AttributeKey.fmtp, pt).getValue();
                    String[] fmtpParts = offeredFmtp.split("[\\s|\\;]");
                    for (String encName : videoEncNames) {
                        if (encName.equalsIgnoreCase(videoCodecName)) {
                            selectedVideoCodec = RTPCodecEnum.getByEncodingName(encName);
                            if (RTPCodecEnum.getByEncodingName(encName) == RTPCodecEnum.H264_PMODE1 && offeredFmtp.contains("packetization-mode=1")) {
                                // sort out the differing h264 profiles
                                H264Profile selected = H264Profile.None;
                                String profile = "42e01f";
                                String profileSent = null;
                                for (String part : fmtpParts) {
                                    int idx = -1;
                                    if ((idx = part.indexOf("profile-level-id")) != -1) {
                                        profileSent = part.substring(idx + 17);
                                        int results = acceptProfile(selected, profileSent);
                                        if (results > selected.ordinal()) {
                                            log.debug("Upgrading profile to payload id: {}, {}", pt, profileSent);
                                            selected = H264Profile.valueOf(results);
                                            profile = profileSent;
                                            // add a media field to our local sdp
                                            MediaField video = new MediaField(mediaType, 9, MediaField.PROTOCOL_UDP, 1);
                                            videoPayloadType = pt;
                                            AttributeField fmtp = new AttributeField(AttributeKey.fmtp, String.format("%d profile-level-id=%s;level-asymmetry-allowed=1;packetization-mode=1", videoPayloadType, profile));
                                            video.addAttributeField(fmtp);
                                            localSdp.addMediaDescription(video);
                                            break;
                                        } else {
                                            log.debug("Skipping profile in sdp offer: {}", profileSent);
                                        }
                                    }
                                }
                            }
                            log.debug("Video streams in local sdp: {}", localSdp.getVideo());
                            break;
                        }
                    }
                }
                bandwidth = media.getBandwidth();
                if (bandwidth != null) {
                    videoBR = bandwidth.getBandwidth();
                    requestedVideoBitrate = videoBR * 1000;
                } else {
                    requestedVideoBitrate = videoBR * 1000;
                }
                log.debug("Video streams bitrate: {}", requestedVideoBitrate);
            } else if (SDPMediaType.application.equals(mediaType)) {
                MediaField offeredData = offerSdp.getMediaDescription(SDPMediaType.application);
                MediaField data = null;
                AttributeField sctpMap = offeredData.getAttribute(AttributeKey.sctpmap);
                if (sctpMap != null) {
                    offeredSctp = sctpMap.getValue().split("[\\s]");
                    log.debug("SCTP map: {}", Arrays.toString(offeredSctp));
                    data = new MediaField(mediaType, 9, MediaField.PROTOCOL_SCTP, new int[] { Integer.valueOf(offeredSctp[0]) });
                } else {
                    // look for sctp-port instead
                    AttributeField sctpPort = offeredData.getAttribute(AttributeKey.sctpport);
                    if (sctpPort != null) {
                        data = new MediaField(mediaType, 9, MediaField.PROTOCOL_UDP_SCTP, new int[] { Integer.valueOf(sctpPort.getValue()) });
                    }
                }
                localSdp.addMediaDescription(data);
                log.debug("Datachannel stream added to local sdp");
                offeredDataChannel = true;
            }
        }
        // setup DTLS
        setupDTLS(offeredDataChannel);
        // setup ice (controlling = true, non-controlling = false)
        setupICE(true);
        // set the props
        setRemoteProperties(offerSdp);
        DtlsControl control = null;
        // create the media streams
        AudioMediaStreamImpl audioMediaStream = null;
        if (selectedAudioCodec != RTPCodecEnum.NONE) {
            // get the "audio" srtp control
            control = controls.get("audio");
            audioMediaStream = new AudioMediaStreamImpl(null, null, control);
            audioMediaStream.setOwner(conn);
            // create audio input format
            AudioFormat audioCodecFormat = new AudioFormat(AudioConstants.OPUS_RTP, 48000d, 16, 2);
            // add our device which route data to the rtmp stream
            audioMediaStream.setDevice(new RTMPAudioOutputDevice(muxer, audioCodecFormat, AudioConstants.AAC_RTMP, requestedAudioBitrate));
            // create media formats based on sdp formats
            MediaFormat audioFormat = MediaFormatImpl.createInstance(audioCodecFormat);
            log.trace("Audio format: {}", audioFormat);
            // set the format of the rtp stream
            audioMediaStream.setFormat(audioFormat);
            // use selected audio codecs payload type if we didnt get on during init
            audioPayloadType = audioPayloadType == -1 ? selectedAudioCodec.getPayloadType() : audioPayloadType;
            log.debug("Audio codec payload type: {}", audioPayloadType);
            audioMediaStream.addDynamicRTPPayloadType((byte) audioPayloadType, audioFormat);
            // receive only
            audioMediaStream.setDirection(MediaDirection.RECVONLY);
            // add the selected payload id to the media description
            MediaField audio = localSdp.getMediaDescription(SDPMediaType.audio);
            if (audio != null) {
                String rtpmap = RTPCodecEnum.getRTPMapString(selectedAudioCodec);
                if (selectedAudioCodec.payloadType != audioPayloadType) {
                    rtpmap = rtpmap.replace(String.valueOf(selectedAudioCodec.payloadType), String.valueOf(audioPayloadType));
                }
                audio.addAttributeField(new AttributeField(AttributeKey.rtpmap, rtpmap));
                audio.setFormats(new int[] { audioPayloadType });
                // set the payload on the media stream
                audioMediaStream.setPayloadType(audioPayloadType);
            }
        } else {
            log.debug("No audio codec selected");
            // remove audio media from sdp
            MediaField audio = localSdp.getMediaDescription(SDPMediaType.audio);
            if (audio != null) {
                localSdp.remove(audio);
            }
        }
        VideoMediaStreamImpl videoMediaStream = null;
        if (selectedVideoCodec != RTPCodecEnum.NONE) {
            if (control == null) {
                // get the "video" srtp control
                control = controls.get("video");
            }
            // seems redundant, but its not if audio control is the master
            videoMediaStream = new VideoMediaStreamImpl(null, null, controls.get("video"));
            videoMediaStream.setOwner(conn);
            // create video input format
            VideoFormat videoCodecFormat = null;
            Map<String, String> parameters = new HashMap<>();
            if (videoBR > 0) {
                parameters.put(VideoConstants.BITRATE, String.valueOf(videoBR));
            }
            if (targetFPS > 0) {
                parameters.put(VideoConstants.FRAMES_PER_SECOND, String.valueOf(targetFPS));
            }
            switch (selectedVideoCodec) {
                case VP8:
                    videoCodecFormat = new ParameterizedVideoFormat(VideoConstants.VP8_RTP, parameters);
                    break;
                case H264_PMODE1:
                default:
                    parameters.put(VideoConstants.H264_PACKETIZATION_MODE_FMTP, "1");
                    videoCodecFormat = new ParameterizedVideoFormat(VideoConstants.H264_RTP, parameters);
                    break;
            }
            // add our devices which route data to the rtmp stream
            videoMediaStream.setDevice(new RTMPVideoOutputDevice(muxer, videoCodecFormat, VideoConstants.H264_RTMP));
            // create media formats based on sdp formats
            MediaFormat videoFormat = MediaFormatImpl.createInstance(videoCodecFormat, 90000.0, parameters, EMPTY_MAP);
            log.trace("Video format: {}", videoFormat);
            // set the format of the rtp stream
            videoMediaStream.setFormat(videoFormat);
            // use selected video codecs payload type if we didnt get on during init
            videoPayloadType = videoPayloadType == -1 ? selectedVideoCodec.getPayloadType() : videoPayloadType;
            log.debug("Video codec payload type: {}", videoPayloadType);
            videoMediaStream.addDynamicRTPPayloadType((byte) videoPayloadType, videoFormat);
            videoMediaStream.setDirection(MediaDirection.RECVONLY);
            // add the selected payload id to the media description
            MediaField video = localSdp.getMediaDescription(SDPMediaType.video);
            if (video != null) {
                String rtpmap = RTPCodecEnum.getRTPMapString(selectedVideoCodec);
                if (selectedVideoCodec.payloadType != videoPayloadType) {
                    rtpmap = rtpmap.replace(String.valueOf(selectedVideoCodec.payloadType), String.valueOf(videoPayloadType));
                }
                video.addAttributeField(new AttributeField(AttributeKey.rtpmap, rtpmap));
                video.setFormats(new int[] { videoPayloadType });
                // set the payload on the media stream
                videoMediaStream.setPayloadType(videoPayloadType);
            }
        } else {
            log.debug("No video codec selected");
            // remove video media from sdp
            MediaField video = localSdp.getMediaDescription(SDPMediaType.video);
            if (video != null) {
                log.info("removing local video");
                localSdp.remove(video);
            }
        }
        DataMediaStreamImpl dataMediaStream = null;
        if (offeredDataChannel) {
            // ensure a master control is set
            if (control == null) {
                // get the "data" srtp control
                control = controls.get("data");
            }
            dataMediaStream = new DataMediaStreamImpl(null, controls.get("data"));
            dataMediaStream.setOwner(conn);
            // send and receive (keep in-mind that this is different from the single direction of the a/v portion)
            dataMediaStream.setDirection(MediaDirection.SENDRECV);
            if (offeredSctp != null) {
                dataMediaStream.setSctpPortNumber(Integer.valueOf(offeredSctp[0]));
                dataMediaStream.setStreamCount(Integer.valueOf(offeredSctp[2]));
            }
        }
        // if we're bundling, create the bundle stream
        mediaStream = new WhipMediaStreamBundle(conn, audioMediaStream, videoMediaStream, dataMediaStream);
        mediaStream.setSrtpControl(control);
        log.trace("init - exit");
    }

    public void setupDTLS(boolean offeredDataChannel) {
        log.debug("setupDTLS control: {}", dtlsControlSetup);
        DtlsControlImpl audioControl = null;
        if (selectedAudioCodec != RTPCodecEnum.NONE) {
            audioControl = new DtlsControlImpl();
            audioControl.setMasterSession(true);
            controls.put("audio", audioControl);
        }
        DtlsControlImpl videoControl = null;
        if (selectedVideoCodec != RTPCodecEnum.NONE) {
            if (selectedAudioCodec != RTPCodecEnum.NONE) {
                videoControl = new DtlsControlImpl(audioControl);
                videoControl.setMultistream(audioControl);
            } else {
                videoControl = new DtlsControlImpl();
                videoControl.setMasterSession(true);
            }
            controls.put("video", videoControl);
        }
        if (offeredDataChannel) {
            DtlsControlImpl dataControl = null;
            if (audioControl != null && audioControl.isMaster()) {
                dataControl = new DtlsControlImpl(audioControl);
                dataControl.setMultistream(audioControl);
            } else if (videoControl != null && videoControl.isMaster()) {
                dataControl = new DtlsControlImpl(videoControl);
                dataControl.setMultistream(videoControl);
            } else {
                dataControl = new DtlsControlImpl();
                dataControl.setMasterSession(true);
            }
            controls.put("data", dataControl);
        }
    }

    @SuppressWarnings("incomplete-switch")
    public void setupICE(boolean controlling) {
        log.debug("setupICE: {} controlling: {} {}", getName(), controlling, conn);
        Transport transport = conn.getTransport();
        if (transport == Transport.UDP) {
            agent.setControlling(controlling);
        } else {
            // if tcp, use controlled / passive, browsers send active candidates with masked ports
            agent.setControlling(false);
        }
        agent.setTrickling(false);
        log.trace("Agent state: {}", agent.getState());
        // create latch
        iceSetupLatch = new CountDownLatch(1);
        // use a property change listener
        agent.addStateChangeListener((evt) -> {
            if (isTrace) {
                log.trace("Change event: {}", evt);
            }
            final IceProcessingState state = (IceProcessingState) evt.getNewValue();
            switch (state) {
                case COMPLETED:
                    log.debug("ICE connectivity completed: {}", getName());
                    break;
                case FAILED:
                    log.warn("ICE connectivity failed for: {} port: {}", getName(), allocatedPort);
                    // set a close message
                    conn.close("ICE failure", true);
                    stop();
                    break;
                case TERMINATED:
                    log.debug("ICE connectivity terminated: {}", getName());
                    // ensure we're starting
                    if (starting.get()) {
                        iceSetupLatch.countDown();
                    } else {
                        log.warn("ICE sockets not starting for: {}", getName());
                    }
                    break;
            }
        });
        try {
            configureMediaPorts(agent.createMediaStream(RTPCodec.MEDIA_0));
        } catch (BindException e) {
            log.warn("Exception in setupICE for: {}", getName());
        }
    }

    private void configureMediaPorts(IceMediaStream stream) throws BindException {
        Transport transport = conn.getTransport();
        log.debug("Preferred transport for ICE media: {}", transport);
        Component component = null;
        int port = PortManager.getRTPServerPort();
        log.info("Attempting to use port: {} for {}", port, getName());
        try {
            // transport passed is used by the host harvester, its not stored nor does it
            // cause other transports to be excluded.
            component = agent.createComponent(stream, transport, port, port, port);
            // if component creation fails "rtp" will be null and ex is thrown
            allocatedPort = component.getSocket().getLocalPort();
            if (isDebug) {
                log.debug("Port requested: {} port bound: {}", port, allocatedPort);
            }
            // if the requested port doesnt match the bound port (allocatedPort), clear the original reservation
            if (port != allocatedPort) {
                PortManager.clearRTPServerPort(port);
            }
            // grab the local default candidate
            LocalCandidate localCand = component.getDefaultCandidate();
            // create transport components for our preferred transport
            if (localCand != null) {
                TransportAddress localAddress = localCand.getTransportAddress();
                TransportAddress rflxAddress = new TransportAddress(publicIPAddress, localAddress.getPort(), transport);
                // if the agent is controlling and prflx is enabled, we create a prflx to allow a best-effort connection
                if (WebRTCPlugin.isPrflxEnabled() && agent.isControlling()) {
                    long priority = localCand.computePriorityForType(CandidateType.PEER_REFLEXIVE_CANDIDATE);
                    component.addLocalCandidate(new PeerReflexiveCandidate(rflxAddress, component, localCand, priority));
                } else if (localCand instanceof HostCandidate) {
                    component.addLocalCandidate(new ServerReflexiveCandidate(rflxAddress, (HostCandidate) localCand, null, CandidateExtendedType.STATICALLY_MAPPED_CANDIDATE));
                }
            }
            log.debug("Candidate count for {}: {}", transport, component.getLocalCandidateCount());
        } catch (Throwable t) {
            log.warn("Port: {} allocation failed for {}", port, getName(), t);
            PortManager.clearRTPServerPort(port);
        }
    }

    public boolean start() {
        log.debug("start: {} audio: {} {}k video: {} {}k", sourceStreamName, selectedAudioCodec, audioBR, selectedVideoCodec, videoBR);
        // get started flag
        if (starting.compareAndSet(false, true)) {
            // adjust the stream codec info before we actually start it
            if (EnumSet.of(RTPCodecEnum.NONE, RTPCodecEnum.OPUS).contains(selectedAudioCodec)) {
                proStream.setRtcAudioCodec(selectedAudioCodec);
            }
            if (EnumSet.of(RTPCodecEnum.NONE, RTPCodecEnum.H264_PMODE1, RTPCodecEnum.H264_PMODE0, RTPCodecEnum.VP8).contains(selectedVideoCodec)) {
                proStream.setRtcVideoCodec(selectedVideoCodec);
            }
            StreamCodecInfo info = (StreamCodecInfo) proStream.getCodecInfo();
            log.debug("ProStream codec info: {} {}", info.getAudioCodecName(), info.getVideoCodecName());
            // set an id
            mediaStream.setProperty("id", getName());
            // set start time on media streams and for RTCP use
            mediaStream.setProstreamStartTime(proStream.getCreationTime());
            // start the flash stream
            proStream.start();
            // start publish flash stream
            proStream.startPublishing();
            // start the ICE process
            agent.startConnectivityEstablishment();
            log.debug("Connectivity establishment in process");
            // wait maxICETimeoutMs time for latch items to complete
            int maxICETimeoutMs = WebRTCPlugin.getIceConnectTimeout();
            try {
                log.debug("Waiting for ICE {} ms", maxICETimeoutMs);
                if (iceSetupLatch.await(maxICETimeoutMs, TimeUnit.MILLISECONDS)) {
                    log.debug("ICE establishment is complete");
                    try {
                        if (mediaStream == null) {
                            log.trace("MediaStreams creation failed");
                        } else {
                            // set up the socket and stream connections
                            setupStreamConnectors();
                            // set rsize property for rtcp parsing
                            if (localSdp.getMediaDescriptions()[0].getAttribute(AttributeKey.rtcprsize) != null) {
                                // this gets set on bundle media stream to be shared by a/v media streams
                                mediaStream.setReducedSizeRTCP(true);
                            }
                            mediaStream.start(false);
                            // get the media streams
                            AudioMediaStreamImpl audioMediaStream = mediaStream.getAudioMediaStream();
                            VideoMediaStreamImpl videoMediaStream = mediaStream.getVideoMediaStream();
                            DataMediaStreamImpl dataMediaStream = mediaStream.getDataMediaStream();
                            if (audioMediaStream != null) {
                                // set/get the remote / source ssrc
                                int localSsrc = audioMediaStream.getLocalSourceID();
                                String[] ssrcAttrParts = offerSdp.getMediaDescription(SDPMediaType.audio).getAttribute(AttributeKey.ssrc).getValue().split("\\s");
                                log.trace("SSRC (audio) attr parts: {}", Arrays.toString(ssrcAttrParts));
                                int remoteAudioSSrc = Integer.parseUnsignedInt(ssrcAttrParts[0]);
                                audioMediaStream.addRemoteSourceID(remoteAudioSSrc);
                                log.info("SSRC (audio) local: {} remote: {}", Integer.toUnsignedString(localSsrc), ssrcAttrParts[0]);
                                // sending audio bitrate if we're an audio-only stream only
                                if (videoMediaStream == null) {
                                    final AudioMediaStreamImpl ams = audioMediaStream;
                                    WebRTCPlugin.submit(() -> {
                                        Thread.currentThread().setName(String.format("AFBSend@%s:%s", sourceStreamName, Integer.toUnsignedString(localSsrc)));
                                        try {
                                            do {
                                                // exit sleeping loop once DTLS is ready
                                                if (audioMediaStream.isDTLSOutputReady()) {
                                                    log.info("DTLS is ready");
                                                    sendRemb(ams, remoteAudioSSrc, requestedAudioBitrate);
                                                    break;
                                                }
                                                Thread.sleep(200L);
                                            } while (!stopped.get());
                                        } catch (Throwable t) {
                                        }
                                    });
                                }
                            }
                            if (videoMediaStream != null) {
                                // set/get the remote / source ssrc
                                final int localSsrc = videoMediaStream.getLocalSourceID();
                                String[] ssrcAttrParts = offerSdp.getMediaDescription(SDPMediaType.video).getAttribute(AttributeKey.ssrc).getValue().split("\\s");
                                log.trace("SSRC (video) attr parts: {}", Arrays.toString(ssrcAttrParts));
                                int remoteVideoSSrc = Integer.parseUnsignedInt(ssrcAttrParts[0]);
                                videoMediaStream.addRemoteSourceID(remoteVideoSSrc);
                                log.debug("SSRC (video) local: {} remote: {}", Integer.toUnsignedString(localSsrc), ssrcAttrParts[0]);
                                final VideoMediaStreamImpl vms = videoMediaStream;
                                // setup a task to send feedback to the source / browser
                                WebRTCPlugin.submit(() -> {
                                    Thread.currentThread().setName(String.format("VFBSend@%s:%s", sourceStreamName, Integer.toUnsignedString(localSsrc)));
                                    try {
                                        do {
                                            // exit sleeping loop once DTLS is ready
                                            if (vms.isDTLSOutputReady()) {
                                                log.info("DTLS is ready, awaiting keyframe");
                                                sendRemb(vms, remoteVideoSSrc, requestedVideoBitrate);
                                                vms.sendPli(remoteVideoSSrc);
                                                break;
                                            }
                                            Thread.sleep(200L);
                                        } while (!stopped.get());
                                        log.debug("Starting feedback");
                                        // do remb/tmmbr
                                    } catch (InterruptedException e) {
                                        // no-op we expect this may happen
                                    } catch (Throwable t) {
                                        log.warn("Exception in feedback sender", t);
                                    }
                                });
                            }
                            // set ao or vo streaming to meta in case someone is interested down the line
                            if (dataMediaStream != null) {
                                // set/get the remote / source ssrc (not SSRC's here, they are SCTP port numbers)
                                final int localSsrc = dataMediaStream.getLocalSourceID();
                                AttributeField desc = offerSdp.getMediaDescription(SDPMediaType.application).getAttribute(AttributeKey.sctpmap);
                                // try sctp-port
                                if (desc == null) {
                                    desc = offerSdp.getMediaDescription(SDPMediaType.application).getAttribute(AttributeKey.sctpport);
                                }
                                if (desc != null) {
                                    String[] ssrcAttrParts = desc.getValue().split("\\s");
                                    log.trace("SCTP attr parts: {}", Arrays.toString(ssrcAttrParts));
                                    int remoteSsrc = Integer.parseUnsignedInt(ssrcAttrParts[0]);
                                    dataMediaStream.addRemoteSourceID(remoteSsrc);
                                    log.info("SCTP local: {} remote: {}", Integer.toUnsignedString(localSsrc), ssrcAttrParts[0]);
                                }
                            }
                            // started!
                            started = true;
                        }
                    } catch (Throwable e) {
                        log.warn("Exception in start", e);
                    }
                } else {
                    log.debug("ICE establishment failed for: {}", sourceStreamName);
                    stop();
                }
            } catch (InterruptedException e) {
                log.warn("Countdown latch interrupted", e);
                stop();
            }
        }
        log.debug("start - exit");
        return started;
    }

    void setupStreamConnectors() {
        log.debug("Setting up the socket and stream connections on {}", getName());
        // get the total stream count
        int streamCount = agent.getStreamCount();
        log.debug("ICE streams: {}", streamCount);
        // get the names on the ice media streams
        List<String> names = agent.getStreamNames();
        log.debug("Checking ICE stream: {}", names);
        // we only bundle, we're expecting only a single ice media stream
        IceMediaStream iceMediaStream = agent.getStreams().get(0);
        if (isTrace) {
            log.trace("ICE: {}", iceMediaStream);
        }
        CandidatePair rtpPair = iceMediaStream.getComponent(1).getSelectedPair();
        TransportAddress addr = rtpPair.getRemoteCandidate().getTransportAddress();
        log.debug("Remote address: {}", addr);
        MediaStreamTarget mediaStreamTarget = new MediaStreamTarget(addr);
        log.debug("Stream target for {} - {}", iceMediaStream.getName(), mediaStreamTarget);
        LocalCandidate localCandidate = rtpPair.getLocalCandidate();
        // if there is only one stream, it could be audio or video or bundle
        StreamConnector streamConnector = new NIOStreamConnector(localCandidate);
        streamConnector.setName(getName());
        log.debug("Setting connector and target: {}", mediaStream);
        mediaStream.setConnector(streamConnector);
        mediaStream.setTarget(mediaStreamTarget);
        // set local connector reference
        this.streamConnector = streamConnector;
    }

    private void sendRemb(MediaStreamImpl mediaStream, int remoteSsrc, int bitrate) throws IOException {
        if (mediaStream != null) {
            mediaStream.maybeSendRemb(remoteSsrc, bitrate);
        }
    }

    public IProStream getProStream() {
        if (proStream == null) {
            log.info("Creating new pro stream {}", scope);
            try {
                // instance a new flash stream via spring
                proStream = (ProStream) scope.getContext().getBean("clientBroadcastStream");
                proStream.setSourceType(SourceType.RTC);
                proStream.setScope(scope);
                proStream.setName(sourceStreamName);
                proStream.setPublishedName(sourceStreamName);
                proStream.setStreamId(1);
                proStream.setRegisterJMX(false);
            } catch (Exception e) {
                log.warn("Exception getting stream instance", e);
            }
        }
        return proStream;
    }

    @Override
    public void stop() {
        log.info("Publisher {} stop, stopped? {}", sourceStreamName, stopped);
        Red5.setConnectionLocal(conn);
        if (stopped.compareAndSet(false, true)) {
            // reset flag
            starting.set(false);
            // ensure close was called on the stream via stream service if not force it
            if (proStream != null && !proStream.isClosed()) {
                // get the broadcast scope from the stream
                IScope bsScope = proStream.getScope();
                // get the scope context
                IContext context = bsScope.getContext();
                IProviderService providerService = (IProviderService) context.getBean(IProviderService.BEAN_NAME);
                // unregister the prostream and its scope
                if (!providerService.unregisterBroadcastStream(scope, sourceStreamName, proStream)) {
                    log.debug("Stream {} unregister returned false", sourceStreamName);
                }
                // close calls stop
                proStream.close();
                proStream = null;
            }
            // stop a/v streams or just dtls control in the case of subscribers
            stopAudio();
            stopVideo();
            // clean up the bundle
            if (mediaStream != null) {
                mediaStream.stop();
                mediaStream = null;
            }
            // clear the media stream controls map; clear only after stopAudio/stopVideo
            controls.clear();
        }
        // clean up ICE agent
        if (agent != null) {
            // clean up via agent.free() wherein streams and components are freed
            agent.free();
            agent = null;
        }
        // clear the ports that were used
        if (allocatedPort > 0) {
            log.info("Deallocating port: {}", allocatedPort);
            PortManager.clearRTPServerPort(allocatedPort);
            allocatedPort = 0;
        }
        // deactivate the muxmaster after the prostream is stopped
        if (muxer != null) {
            muxer.deactivate();
            muxer = null;
        }
        if (offerSdp != null) {
            offerSdp.free();
            offerSdp = null;
        }
        if (localSdp != null) {
            localSdp.free();
            localSdp = null;
        }
        Red5.setConnectionLocal(null);
    }

    void stopAudio() {
        if (selectedAudioCodec != RTPCodecEnum.NONE) {
            log.debug("Stopping / closing audio stream");
            selectedAudioCodec = RTPCodecEnum.NONE;
            if (mediaStream != null) {
                // get the media stream
                AudioMediaStreamImpl audioMediaStream = mediaStream.getAudioMediaStream();
                if (audioMediaStream != null) {
                    // stop vs close since close will kill the socket and skip bundle media stream cleanup
                    audioMediaStream.stop();
                    DtlsControl control = controls.remove("audio");
                    if (control != null) {
                        control.cleanup(audioMediaStream);
                    }
                }
            }
        }
    }

    void stopVideo() {
        if (selectedVideoCodec != RTPCodecEnum.NONE) {
            log.debug("Stopping / closing video stream");
            selectedVideoCodec = RTPCodecEnum.NONE;
            if (mediaStream != null) {
                // get the media stream
                VideoMediaStreamImpl videoMediaStream = mediaStream.getVideoMediaStream();
                if (videoMediaStream != null) {
                    if (videoMediaStream.getDevice() instanceof RTMPVideoOutputDevice) {
                        ((RTMPVideoOutputDevice) videoMediaStream.getDevice()).destroy();
                    }
                    // stop vs close since close will kill the socket and skip bundle media stream cleanup
                    videoMediaStream.stop();
                    DtlsControl control = controls.remove("video");
                    if (control != null) {
                        control.cleanup(videoMediaStream);
                    }
                }
            }
        }
    }

    /**
     * Create the answer SDP based on the offer.
     */
    public void createAnswer() {
        log.trace("createAnswer");
        // update the session version counter
        long sessionVersion = 1L;
        try {
            sessionVersion = offerSdp.getOrigin().getSessionVersion() + 1L;
        } catch (Exception e) {
        }
        // set the origin
        OriginField origin = new OriginField(String.format("red5pro_%s", sourceStreamName), IdGenerator.generateNumericStringId(13), sessionVersion, "0.0.0.0");
        localSdp.setOrigin(origin);
        // set bundling
        localSdp.setBundle(true);
        // get the media streams
        AudioMediaStreamImpl audioMediaStream = mediaStream.getAudioMediaStream();
        VideoMediaStreamImpl videoMediaStream = mediaStream.getVideoMediaStream();
        DataMediaStreamImpl dataMediaStream = mediaStream.getDataMediaStream();
        DtlsControl dtlsControl = null;
        // grab our candidates
        List<String> cands = getLocalCandidates();
        if (audioMediaStream != null) {
            MediaField audio = localSdp.getMediaDescription(SDPMediaType.audio);
            log.debug("Audio streams in local sdp: {}", localSdp.getAudio());
            if (localSdp.hasAudio()) {
                audio.setConnection(new ConnectionField(publicIPAddress));
                if (audioBR > 0) {
                    audio.setBandwidth(new BandwidthField(audioBR));
                }
                dtlsControl = controls.get("audio");
                audio.addAttributeField(new AttributeField(AttributeKey.iceufrag, agent.getLocalUfrag()));
                audio.addAttributeField(new AttributeField(AttributeKey.icepwd, agent.getLocalPassword()));
                if (agent.isTrickling()) {
                    audio.addAttributeField(new AttributeField(AttributeKey.iceoptions, "trickle"));
                }
                audio.addAttributeField(new AttributeField(AttributeKey.fingerprint, dtlsControl.getLocalFingerprintHashFunction() + ' ' + dtlsControl.getLocalFingerprint()));
                audio.addAttributeField(new AttributeField(AttributeKey.setup, dtlsControlSetup.toString()));
                audio.addAttributeField(new AttributeField(AttributeKey.mid, offerSdp.getMediaDescription(SDPMediaType.audio).getMediaId()));
                // add our candidates
                // a=candidate:1 1 udp 2015363583 192.168.1.218 49207 typ host
                cands.forEach(c -> {
                    audio.addAttributeField(new AttributeField(AttributeKey.candidate, c));
                });
                // a=end-of-candidates
                audio.addAttributeField(new AttributeField(AttributeKey.endofcandidates, null));
                audio.addAttributeField(new AttributeField(AttributeKey.recvonly, null));
                audio.addAttributeField(new AttributeField(AttributeKey.rtcpmux, null));
                audio.addAttributeField(new AttributeField(AttributeKey.rtcprsize, null));
                int pt = audio.getFormats()[0];
                if (selectedAudioCodec == RTPCodecEnum.OPUS) {
                    List<MediaField> offerredAudio = offerSdp.getMediaDescriptions(SDPMediaType.audio);
                    if (!offerredAudio.isEmpty()) {
                        for (MediaField offerAudio : offerredAudio) {
                            if (offerAudio.getAttribute(AttributeKey.rtpmap).getValue().contains(selectedAudioCodec.encodingName)) {
                                audio.addAttributeField(new AttributeField(AttributeKey.fmtp, String.format("%d minptime=10;sprop-stereo=1;stereo=1;useinbandfec=0;maxaveragebitrate=%d;cbr=0", pt, requestedAudioBitrate)));
                                break;
                            }
                        }
                    }
                }
            } else {
                // audio codec selection failed if we're here
                localSdp.remove(audio);
                stopAudio();
            }
        }
        // if there is video, get the attrs for the SDP
        if (videoMediaStream != null) {
            MediaField video = localSdp.getMediaDescription(SDPMediaType.video);
            log.debug("Video streams in local sdp: {}", localSdp.getVideo());
            if (localSdp.hasVideo()) {
                MediaField offeredVideo = offerSdp.getMediaDescription(SDPMediaType.video);
                video.setConnection(new ConnectionField(publicIPAddress));
                if (videoBR > 0) {
                    video.setBandwidth(new BandwidthField(videoBR));
                }
                dtlsControl = controls.get("video");
                video.addAttributeField(new AttributeField(AttributeKey.iceufrag, agent.getLocalUfrag()));
                video.addAttributeField(new AttributeField(AttributeKey.icepwd, agent.getLocalPassword()));
                if (agent.isTrickling()) {
                    video.addAttributeField(new AttributeField(AttributeKey.iceoptions, "trickle"));
                }
                video.addAttributeField(new AttributeField(AttributeKey.fingerprint, dtlsControl.getLocalFingerprintHashFunction() + ' ' + dtlsControl.getLocalFingerprint()));
                video.addAttributeField(new AttributeField(AttributeKey.setup, dtlsControlSetup.toString()));
                video.addAttributeField(new AttributeField(AttributeKey.mid, offeredVideo.getMediaId()));
                // add our candidates
                // a=candidate:1 1 udp 2015363583 192.168.1.218 49207 typ host
                cands.forEach(c -> {
                    video.addAttributeField(new AttributeField(AttributeKey.candidate, c));
                });
                // a=end-of-candidates
                video.addAttributeField(new AttributeField(AttributeKey.endofcandidates, null));
                video.addAttributeField(new AttributeField(AttributeKey.recvonly, null));
                video.addAttributeField(new AttributeField(AttributeKey.rtcpmux, null));
                video.addAttributeField(new AttributeField(AttributeKey.rtcprsize, null));
                int pt = video.getFormats()[0];
                video.addAttributeField(new AttributeField(AttributeKey.rtcpfb, String.format("%d nack", pt)));
                video.addAttributeField(new AttributeField(AttributeKey.rtcpfb, String.format("%d nack pli", pt)));
                if (offeredVideo.hasAttributeWithValue(AttributeKey.rtcpfb, "goog-remb")) {
                    video.addAttributeField(new AttributeField(AttributeKey.rtcpfb, String.format("%d goog-remb", pt)));
                }
                if (offerSdp.isFirefox()) {
                    List<MediaField> offerredVideo = offerSdp.getMediaDescriptions(SDPMediaType.video);
                    if (!offerredVideo.isEmpty()) {
                        boolean gotMaxFs = false;
                        for (MediaField offerVideo : offerredVideo) {
                            if (offerVideo.getAttribute(AttributeKey.rtpmap).getValue().contains(selectedVideoCodec.encodingName)) {
                                AttributeField offerVideoFmtp = offerVideo.getAttribute(AttributeKey.fmtp);
                                if (offerVideoFmtp != null && offerVideoFmtp.getValue().contains("max-fs")) {
                                    gotMaxFs = true;
                                }
                                break;
                            }
                        }
                        if (!gotMaxFs) {
                            video.addAttributeField(new AttributeField(AttributeKey.fmtp, String.format("%d max-fs=12288", pt)));
                        }
                    }
                }
            } else {
                // video codec selection failed if we're here
                localSdp.remove(video);
                stopVideo();
            }
        }
        if (dataMediaStream != null) {
            MediaField data = localSdp.getMediaDescription(SDPMediaType.application);
            if (data != null) {
                MediaField offeredData = offerSdp.getMediaDescription(SDPMediaType.application);
                data.setConnection(new ConnectionField(publicIPAddress));
                dtlsControl = controls.get("data");
                data.addAttributeField(new AttributeField(AttributeKey.iceufrag, agent.getLocalUfrag()));
                data.addAttributeField(new AttributeField(AttributeKey.icepwd, agent.getLocalPassword()));
                data.addAttributeField(new AttributeField(AttributeKey.fingerprint, dtlsControl.getLocalFingerprintHashFunction() + ' ' + dtlsControl.getLocalFingerprint()));
                data.addAttributeField(new AttributeField(AttributeKey.setup, dtlsControlSetup.toString()));
                // get media id from offer
                data.addAttributeField(new AttributeField(AttributeKey.mid, offeredData.getMediaId()));
                // data channel is send and receive
                data.addAttributeField(new AttributeField(AttributeKey.sendrecv, null));
                // use the newer sctp format
                data.addAttributeField(new AttributeField(AttributeKey.sctpport, String.valueOf(dataMediaStream.getLocalSourceID())));
                data.addAttributeField(new AttributeField(AttributeKey.maxmessagesize, String.format("%d", maximumMessageSize)));
            }
        }
        // should pretty-print
        log.debug("Generated answer: {}", localSdp);
        // get answer sdp as a string
        //answerSdp = localSdp.toString().replaceAll("[\\r\\n|\\n]", "\\\\n"); // no cr/crlf replacing for direct http response
        answerSdp = localSdp.toString();
    }

    /**
     * Returns the offer SDP.
     *
     * @return offer sdp
     */
    @Override
    public SessionDescription getSdp() {
        return offerSdp;
    }

    /**
     * Set the offer SDP and configure the ProStream based on the published media
     *
     * @param sdp
     */
    public void setOffer(SessionDescription sdp) {
        this.offerSdp = sdp;
        log.debug("Offer: {}", sdp);
        // grab codec info
        StreamCodecInfo codecInfo = (StreamCodecInfo) proStream.getCodecInfo();
        if (codecInfo == null) {
            log.warn("ProStream was missing codec info, this should never happen");
        }
        // from the SDP offer, determine if media consists of audio and/or video
        boolean needsConfigAudio = offerSdp.hasAudio();
        boolean needsConfigVideo = offerSdp.hasVideo();
        if (log.isDebugEnabled()) {
            log.debug("Publisher stream needs - audio: {}  video: {}", needsConfigAudio, needsConfigVideo);
        }
        if (needsConfigAudio) {
            codecInfo.setAudioCodec(new AACAudio());
        }
        if (needsConfigVideo) {
            codecInfo.setVideoCodec(new AVCVideo());
        }
        if (log.isDebugEnabled()) {
            log.debug("Publisher stream - audio: {}  video: {}", codecInfo.getAudioCodecName(), codecInfo.getVideoCodecName());
        }
    }

    /**
     * Returns an answer SDP.
     */
    @Override
    public String getLocalSdp() {
        if (offerSdp == null) {
            log.error("No offer sdp is set, answer creation and init will fail");
            return null;
        }
        if (answerSdp == null) {
            createAnswer();
        }
        return answerSdp;
    }

    /**
     * Returns the answer sdp in SessionDescription form.
     *
     * @return answer sdp
     */
    public SessionDescription getAnswerSdp() {
        // ensure the answer has been generated
        if (answerSdp == null) {
            createAnswer();
        }
        return localSdp;
    }

    public void setAudioBR(int kbs) {
        audioBR = kbs;
    }

    public void setVideoBR(int kbs) {
        videoBR = kbs;
    }

    public void setFPS(int fps) {
        this.targetFPS = fps;
    }

    private int acceptProfile(H264Profile current, String profileSent) {
        int profile = Integer.parseInt(profileSent.substring(0, 2), 16);//0,1     
        int flags = Integer.parseInt(profileSent.substring(2, 4), 16);//2,3
        boolean contraint0 = ((flags >> 7) & 0x1) == 1;
        H264Profile eval = H264Profile.None;
        if (profile == 100) {
            eval = H264Profile.High;
        } else if (profile == 77) {
            eval = H264Profile.Main;
        } else if (profile == 66) {
            if (contraint0) {
                eval = H264Profile.ConstrainedBaseline;
            } else {
                eval = H264Profile.Baseline;
            }
        }
        if (eval.ordinal() > 0 && current.compareTo(eval) < 0 && eval.compareTo(H264Profile.Main) <= 0) {
            return eval.ordinal();
        }
        return current.ordinal();
    }

    @Override
    public String getName() {
        return sourceStreamName;
    }

    @Override
    public List<String> getLocalCandidates() {
        log.trace("getLocalCandidates");
        List<String> results = new ArrayList<String>(2);
        if (agent != null) {
            List<String> names = agent.getStreamNames();
            for (String name : names) {
                log.trace("Getting ICE media stream for {}", name);
                agent.getStream(name).getComponents().forEach(component -> {
                    component.getLocalCandidates().forEach(candidate -> {
                        log.debug("Local candidate: {}", candidate);
                        if (candidate.getTransport() == Transport.TCP) {
                            // ensure tcptype is added to tcp candidates missing it
                            if (candidate.getTcpType() == null) {
                                results.add(String.format("%s tcptype %s", candidate.toString(), (agent.isControlling() ? CandidateTcpType.ACTIVE : CandidateTcpType.PASSIVE)));
                            } else {
                                results.add(candidate.toString());
                            }
                        } else if (candidate.getTransport() == Transport.UDP) {
                            results.add(candidate.toString());
                        }
                    });
                });
            }
            Collections.sort(results);
            // change this back to debug after debugging
            if (isDebug) {
                log.info("Local candidate results: {}", results);
            }
        }
        return results;
    }

    @SuppressWarnings("incomplete-switch")
    public void setRemoteProperties(SessionDescription sdp) {
        log.trace("setRemoteProperties");
        // get all the mlines
        MediaField[] medias = sdp.getMediaDescriptions();
        // media elements will be null if none exist in the sdp; also if theres an unbundle answer to a bundled offer
        if (medias != null) {
            // get all the ice streams
            List<IceMediaStream> iceMediaStreams = agent.getStreams();
            for (IceMediaStream iceMediaStream : iceMediaStreams) {
                if (iceMediaStream != null) {
                    // get the first media entry
                    MediaField media = medias[0];
                    log.debug("setRemoteProperties: {} {}", media.getMediaId(), iceMediaStream.getName());
                    // if the media doesnt contain a ice props, check the session
                    String ufrag = media.getAttribute(AttributeKey.iceufrag).getValue();
                    String passwd = media.getAttribute(AttributeKey.icepwd).getValue();
                    if (ufrag == null) {
                        ufrag = sdp.getAttribute(AttributeKey.iceufrag).getValue();
                    }
                    if (passwd == null) {
                        passwd = sdp.getAttribute(AttributeKey.icepwd).getValue();
                    }
                    log.trace("Setting remote audio ufrag: {} passwd: {}", ufrag, passwd);
                    iceMediaStream.setRemoteUfrag(ufrag);
                    iceMediaStream.setRemotePassword(passwd);
                    // setup dtls map
                    Map<String, String> dtlsMap = new HashMap<>();
                    AttributeField fingerPrint = media.getAttribute(AttributeKey.fingerprint);
                    // if the media doesnt contain a fingerprint, check the session
                    if (fingerPrint == null) {
                        fingerPrint = sdp.getAttribute(AttributeKey.fingerprint);
                    }
                    // continue only if we have a fingerprint
                    if (fingerPrint != null) {
                        String[] parts = fingerPrint.getValue().split(" ");
                        dtlsMap.put(parts[0], parts[1]);
                        // check if remote wants to be active and ensure we dont clash
                        AttributeField attr = sdp.getAttribute(AttributeKey.setup) != null ? sdp.getAttribute(AttributeKey.setup) : media.getAttribute(AttributeKey.setup);
                        Setup remoteSetup = Setup.valueOf(attr.getValue().toUpperCase());
                        log.debug("Remote DTLS: {}", remoteSetup);
                        switch (remoteSetup) {
                            case ACTIVE:
                            case ACTPASS:
                                dtlsControlSetup = Setup.PASSIVE;
                                // if we're edge and want to publish, set dtls to active
//                                if (conn.isEdge() && conn.getDuty().equals(Duty.PUBLISHER)) {
//                                    dtlsControlSetup = Setup.ACTIVE;
//                                } else {
//                                    dtlsControlSetup = Setup.PASSIVE;
//                                }
                                break;
                            case PASSIVE:
                                if (dtlsControlSetup.equals(remoteSetup)) {
                                    // we can't both be passive
                                    dtlsControlSetup = Setup.ACTIVE;
                                }
                        }
                        // setup dtls control
                        DtlsControl control = controls.get("audio");
                        if (control != null) {
                            log.trace("Setting remote audio dtls: {}", dtlsMap);
                            control.setRemoteFingerprints(dtlsMap);
                            control.setSetup(dtlsControlSetup);
                        } else {
                            log.debug("Audio control not found");
                        }
                        // handle video if it exists
                        control = controls.get("video");
                        if (control != null) {
                            log.trace("Setting remote video dtls: {}", dtlsMap);
                            control.setRemoteFingerprints(dtlsMap);
                            control.setSetup(dtlsControlSetup);
                        } else {
                            log.debug("Video control not found");
                        }
                        // handle data if it exists
                        control = controls.get("data");
                        if (control != null) {
                            log.trace("Setting remote data dtls: {}", dtlsMap);
                            control.setRemoteFingerprints(dtlsMap);
                            control.setSetup(dtlsControlSetup);
                        } else {
                            log.debug("Datachannel control not found");
                        }
                        // get an candidates
                        Arrays.stream(media.getAttributes(AttributeKey.candidate)).forEach(candAttr -> {
                            String candidate = candAttr.getValue();
                            if (candidate.length() > 0) {
                                setRemoteCandidates(0, candidate);
                            }
                        });
                        // we only want to loop once at most, so lets break out
                        break;
                    } else {
                        log.warn("Fingerprint was not found in the sdp, media: {} will not be available", media.getMediaId());
                    }
                }
            }
        } else {
            log.warn("No media entries exist in the sdp, session is not valid for streaming");
        }
    }

    /** {@inheritDoc} */
    public void setRemoteCandidates(int mlineIndex, String remoteCandidates) {
        log.debug("setRemoteCandidates mlineIndex: {} {}", mlineIndex, remoteCandidates);
        List<IceMediaStream> iceStreams = agent.getStreams();
        if (mlineIndex < iceStreams.size()) {
            IceMediaStream iceMediaStream = iceStreams.get(mlineIndex);
            if (iceMediaStream != null) {
                // parse incoming candidates (usually one-at-a-time)
                List<RemoteCandidate> candidates = CandidateParser.parseRemoteCandidates(iceMediaStream, remoteCandidates);
                // log.debug("Remote candidates at {}: {}", mlineIndex, candidates);
                for (RemoteCandidate candidate : candidates) {
                    // get the "matching" component if it exists
                    Component component = iceMediaStream.getComponent(candidate.getComponentId());
                    if (component != null) {
                        // add the remote candidate to the component
                        component.addRemoteCandidate(candidate);
                        log.debug("Added remote candidate at {}: {}", mlineIndex, candidate);
                    } else {
                        log.warn("No component matching remote candidate at {}: {}", mlineIndex, candidate);
                    }
                }
            } else {
                log.warn("Unhandled mlineIndex: {}", mlineIndex);
            }
        } else {
            log.debug("No ICE stream for index: {}", mlineIndex);
        }
    }

    @Override
    public IScope getScope() {
        return scope;
    }

    @Override
    public boolean isIceController() {
        return agent.isControlling();
    }

    @Override
    public void setSubscriberModeStandby(boolean state) {
    }

    @Override
    public boolean isSubscriberModeStandby() {
        return false;
    }

    @Override
    public IoSession getIoSession() {
        IoSession session = null;
        // prevent any NPE's
        if (streamConnector != null) {
            IceSocketWrapper socket = streamConnector.getSocket();
            if (socket != null) {
                session = socket.getSession();
            }
        }
        return session;
    }

    public boolean isStarting() {
        return starting.get();
    }

    public boolean isStopped() {
        return stopped.get();
    }

    @Override
    public String getMediaId(int index) {
        try {
            return localSdp.getMediaDescriptions()[index].getMediaId();
        } catch (Exception e) {
            log.warn("Invalid media id index: {}", index);
        }
        return null;
    }

    @Override
    public String toString() {
        return "WhipPublisher [starting=" + starting + ", started=" + started + ", sourceStreamName=" + sourceStreamName + ", scope=" + scope.getName() + "]";
    }

}
