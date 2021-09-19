package org.red5.whip;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.ice4j.Transport;
import org.ice4j.TransportAddress;
import org.ice4j.ice.CandidateTcpType;
import org.ice4j.ice.CandidateType;
import org.ice4j.ice.Component;
import org.ice4j.ice.IceMediaStream;
import org.ice4j.ice.RemoteCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minidev.json.JSONObject;

/**
 * Parses ICE candidates.
 * 
 * @author Paul Gregoire
 */
public class CandidateParser {

    private static final Logger log = LoggerFactory.getLogger(CandidateParser.class);

    private static final boolean isDebug = log.isDebugEnabled();

    private static final Pattern IPV6_ADDRESS_PATTERN = Pattern.compile("([a-fA-F0-9:]+:+)+[a-fA-F0-9]+");

    /**
     * Parses incoming string containing ICE candidate entries into RemoteCandidate instances.
     * 
     * @param iceMediaStream
     * @param remoteCandidates
     * @return remote candidates
     */
    public static List<RemoteCandidate> parseRemoteCandidates(IceMediaStream iceMediaStream, String remoteCandidates) {
        log.info("parseRemoteCandidates for: {}\n{}", iceMediaStream.getName(), remoteCandidates);
        List<RemoteCandidate> candidates = new ArrayList<>(1);
        if (remoteCandidates.indexOf("\\n") != -1) {
            String[] split = remoteCandidates.split("\\s*\\Q\\r\\n\\E\\s*");
            log.info("Candidates: {}", split.length);
            for (String s : split) {
                String[] candidate = s.substring(s.indexOf(':')).split(" ");
                /*
                 *  0 foundation
                 *  1 component-id
                 *  2 transport
                 *  3 priority
                 *  4 ip
                 *  5 port
                 *  6 cand-type-marker
                 *  7 candidate type (host, srflx, prflx, relay)
                 *  8 raddr or generation (or tcptype for tcp candidate)
                 *  9 ip
                 * 10 rport
                 * 11 port
                 * --- may or may not exist or in this order 
                 * 12 generation
                 * 13 generation value
                 * 14 ufrag
                 * 15 user fragment
                 * 16 network-id
                 * 17 network id value
                 * 18 network-cost
                 * 19 network cost value 
                 */
                RemoteCandidate remoteCandidate = buildCandidate(iceMediaStream, candidate);
                if (remoteCandidate != null) {
                    candidates.add(remoteCandidate);
                }
            }
        } else {
            int startIndex = remoteCandidates.contains("candidate:") ? remoteCandidates.indexOf("candidate:") : 0;
            String[] candidate = remoteCandidates.substring(startIndex).split("[\\s\"]");
            if (isDebug) {
                log.debug("Candidate array: {}", Arrays.toString(candidate));
            }
            RemoteCandidate remoteCandidate = buildCandidate(iceMediaStream, candidate);
            if (remoteCandidate != null) {
                candidates.add(remoteCandidate);
            }
        }
        if (candidates.size() > 1) {
            // sort so that "host" candidates are on the top
            Collections.sort(candidates);
        }
        if (isDebug) {
            log.debug("Candidate(s): {}", candidates);
        }
        return candidates;
    }

    /**
     * 
       candidate-attribute   = "candidate" ":" foundation SP component-id SP
                               "TCP" SP
                               priority SP
                               connection-address SP
                               port SP
                               cand-type
                               [SP rel-addr]
                               [SP rel-port]
                               SP tcp-type-ext
                               *(SP extension-att-name SP
                                    extension-att-value)
    
        tcp-type-ext          = "tcptype" SP tcp-type
        tcp-type              = "active" / "passive" / "so"
    
     * @param iceMediaStream
     * @param candidate
     * @return
     */
    private static RemoteCandidate buildCandidate(IceMediaStream iceMediaStream, String[] candidate) {
        log.info("buildCandidate for: {} {}", iceMediaStream.getName(), Arrays.toString(candidate));
        RemoteCandidate remoteCandidate = null;
        if (!"end-of-candidates".equals(candidate[0])) {
            Transport transport = "UDP".equals(candidate[2].toUpperCase()) ? Transport.UDP : Transport.TCP;
            //log.trace("Transport: {}", transport);
            // https://blog.mozilla.org/webrtc/active-ice-tcp-punch-firewalls-directly/
            // if its port 9 and transport is tcp then its masked, check the tcptype for 'active' which means our end will listen
            int port = Integer.valueOf(candidate[5]);
            // ensure foundation value is valid
            String foundation = candidate[0];
            int colonIdx = foundation.indexOf(':');
            if (colonIdx != -1) {
                foundation = foundation.substring(colonIdx + 1);
            }
            //log.trace("Foundation: {}", foundation);
            int componentId = Integer.valueOf(candidate[1]);
            Component component = iceMediaStream.getComponent(componentId);
            log.trace("Component: {}", component);
            if (component != null) {
                TransportAddress mainAddr = new TransportAddress(candidate[4], port, transport);
                try {
                    RemoteCandidate relatedCandidate = null;
                    // if there is a rel addr and rel port is not
                    if (candidate.length >= 11 && "raddr".equals(candidate[8])) {
                        TransportAddress relatedAddr = new TransportAddress(candidate[9], Integer.valueOf(candidate[11]), transport);
                        relatedCandidate = component.findRemoteCandidate(relatedAddr);
                        // ensure the component ids match
                        if (relatedCandidate != null && relatedCandidate.getComponentId() != componentId) {
                            // null it out if they dont match
                            relatedCandidate = null;
                        }
                    }
                    //                                                                             type           foundation  componentId  priority
                    remoteCandidate = new RemoteCandidate(mainAddr, component, CandidateType.parse(candidate[7]), foundation, componentId, Long.valueOf(candidate[3]), relatedCandidate);
                    // grab tcptype if we have it
                    if (transport == Transport.TCP) {
                        if (candidate.length >= 9 && "tcptype".equals(candidate[8])) {
                            // candidate:4 1 TCP 2105458943 10.0.1.16 9 typ host tcptype active
                            remoteCandidate.setTcpType(CandidateTcpType.parse(candidate[9]));
                        } else if (candidate.length >= 11 && "tcptype".equals(candidate[10])) {
                            //candidate:2 1 tcp 1684798975 10.0.0.13 51774 typ srflx 10.0.0.13 51774 tcptype active
                            remoteCandidate.setTcpType(CandidateTcpType.parse(candidate[11]));
                        }
                    }
                    // check for generation, network-id, and network-cost
                    if (candidate.length >= 13) {
                        /* 
                         * 12 generation
                         * 13 generation value
                         * 14 ufrag
                         * 15 user fragment
                         * 16 network-id
                         * 17 network id value
                         * 18 network-cost
                         * 19 network cost value 
                         */
                        // candidate:2 1 udp 1686052607 71.222.38.190 49803 typ srflx raddr 192.168.0.134 rport 49803 generation 0 ufrag OsoO network-id 1
                        // candidate:2 1 udp 1685987071 71.222.38.190 50923 typ srflx raddr 192.168.0.155 rport 50923 generation 0 ufrag OsoO network-id 2 network-cost 10
                        // candidate:2 1 udp 2122260224 192.168.0.143 63394 typ host generation 0 ufrag DFmw network-id 3 network-cost 10
                        for (int f = 8; f < candidate.length; f++) {
                            //log.trace("Candidate field: {}", candidate[f]);
                            String val = candidate[f];
                            switch (val) {
                                case "ufrag":
                                    val = candidate[f + 1];
                                    remoteCandidate.setUfrag(val);
                                    break;
                                case "generation":
                                    val = candidate[f + 1];
                                    log.debug("Candidate field: \"{}\"", candidate[f + 1]);
                                    remoteCandidate.setGeneration(Integer.valueOf(val));
                                    break;
                                case "network-id":
                                    val = candidate[f + 1];
                                    log.debug("Candidate field: \"{}\"", candidate[f + 1]);
                                    remoteCandidate.setNetworkId(Integer.valueOf(val));
                                    break;
                                case "network-cost":
                                    val = candidate[f + 1];
                                    log.debug("Candidate field: \"{}\"", candidate[f + 1]);
                                    remoteCandidate.setNetworkCost(Integer.valueOf(val));
                                    break;
                            }
                        }
                    }
                    // all the candidates are added to the component; the component will decide which network id's to drop
                    //component.addRemoteCandidate(remoteCandidate);
                } catch (Exception ex) {
                    log.warn("Build candidate exception", ex);
                }
            } else {
                log.debug("ICE media stream component not found for candidate: {}", Arrays.toString(candidate));
            }
        }
        return remoteCandidate;
    }

    /**
     * Parses a JSON formatted candidate.
     * 
     * @param candidate
     * @return string-style candidate
     */
    public static String parseRemoteCandidate(JSONObject candidate) {
        // Edge: "foundation":"1","priority":2130706431,"ip":"10.0.0.13","protocol":"udp","port":61472,"type":"host","tcpType":"active","relatedAddress":null,"relatedPort":0
        // Chrome:  candidate:976374523 1 udp 1686052607 71.38.119.248 34303 typ srflx raddr 10.0.0.5 rport 34303 generation 0 ufrag PnfW network-id 1
        // Firefox: candidate:1 1 UDP 1685790719 73.143.36.217 61952 typ srflx raddr 10.0.0.111 rport 61952
        String protocol = candidate.getAsString("protocol");
        String raddr = candidate.getAsString("relatedAddress");
        int rport = (int) candidate.getAsNumber("relatedPort");
        // reflexive type
        if (raddr != null && rport > 0) {
            if ("udp".equals(protocol)) {
                return String.format("candidate:%d 1 %s %d %s %d typ %s %s %d", candidate.getAsNumber("foundation"), protocol, candidate.getAsNumber("priority"), candidate.getAsString("ip"), candidate.getAsNumber("port"), candidate.getAsString("type"), raddr, rport);
            } else {
                return String.format("candidate:%d 1 %s %d %s %d typ %s %s %d tcptype %s", candidate.getAsNumber("foundation"), protocol, candidate.getAsNumber("priority"), candidate.getAsString("ip"), candidate.getAsNumber("port"), candidate.getAsString("type"), raddr, rport, candidate.getAsString("tcpType"));
            }
        }
        // host type
        return String.format("candidate:%d 1 %s %d %s %d typ host", candidate.getAsNumber("foundation"), protocol, candidate.getAsNumber("priority"), candidate.getAsString("ip"), candidate.getAsNumber("port"));
    }

    /**
     * Returns an OO style JSON string for the given candidate string.
     * 
     * @param rawCandidate
     * @return json string
     */
    public static String toJson(String rawCandidate) {
        JSONObject candidate = new JSONObject();
        // find out transport
        boolean udp = rawCandidate.contains("udp");
        // reflexive or relay type
        boolean reflexive = false;
        // split on spaces
        String[] parts = rawCandidate.split("\\s");
        for (int p = 0; p < parts.length; p++) {
            switch (p) {
                case 0: // foundation
                    String foundation = parts[p];
                    int colonIdx = foundation.indexOf(':');
                    if (colonIdx != -1) {
                        foundation = foundation.substring(colonIdx + 1);
                    }
                    candidate.put("foundation", Integer.valueOf(foundation));
                    break;
                case 1: // component
                    //int component = Integer.valueOf(parts[p]);
                    //candidate.put("component", component);
                    break;
                case 2: // protocol
                    String protocol = parts[p].toLowerCase();
                    candidate.put("protocol", protocol);
                    break;
                case 3: // priority
                    int priority = Integer.valueOf(parts[p]);
                    candidate.put("priority", priority);
                    break;
                case 4: // ip address
                    String ip = parts[p];
                    candidate.put("ip", ip);
                    break;
                case 5: // port
                    int port = Integer.valueOf(parts[p]);
                    candidate.put("port", port);
                    break;
                case 6: // typ string not used
                    break;
                case 7: // candidate type
                    String type = parts[p];
                    candidate.put("type", type);
                    break;
                case 8: // raddr string, just verify
                    String raddr = parts[p];
                    if ("raddr".equals(raddr)) {
                        reflexive = true;
                    }
                    break;
                case 9: // raddr value
                    if (reflexive) {
                        String raddress = parts[p];
                        candidate.put("relatedAddress", raddress);
                    }
                    break;
                case 10: // rport string
                    // skip
                    break;
                case 11: // rport value
                    if (reflexive) {
                        int rport = Integer.valueOf(parts[p]);
                        candidate.put("relatedPort", rport);
                    }
                    break;
                case 12: // if tcp, look for type string
                    String tcptype = parts[p];
                    if (!udp && "tcptype".equals(tcptype)) {
                        tcptype = parts[p + 1];
                        candidate.put("tcpType", tcptype);
                    }
                    break;
            }
        }
        return candidate.toJSONString();
    }

    /**
     * Returns true if the candidate json contains an IPv6 address.
     * 
     * @param json
     * @return true if IPv6 and false otherwise
     */
    public static boolean isIPv6Candidate(String json) {
        // {"candidate":{"candidate":"candidate:2 2 UDP 2122252542 fd83:6b9b:a38a:2:8def:5885:2ea7:44d1 62275 typ host","sdpMid":"0","sdpMLineIndex":0,"usernameFragment":"058ebd7a"}}
        Matcher matcher = IPV6_ADDRESS_PATTERN.matcher(json);
        return matcher.find();
    }

}
