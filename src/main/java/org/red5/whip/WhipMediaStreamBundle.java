package org.red5.whip;

import org.jitsi.impl.neomedia.AudioMediaStreamImpl;
import org.jitsi.impl.neomedia.BundleMediaStreamImpl;
import org.jitsi.impl.neomedia.MediaStreamImpl;
import org.jitsi.impl.neomedia.VideoMediaStreamImpl;

import com.red5pro.media.DataMediaStreamImpl;

/**
 * Whip specific extension of BundleMediaStreamImpl.
 * 
 * @author Paul Gregoire
 *
 */
public class WhipMediaStreamBundle extends BundleMediaStreamImpl {
    
    public WhipMediaStreamBundle(WhipConnection conn, MediaStreamImpl... mediaStreams) {
        // the bundle "owner"
        owner = conn;
        for (MediaStreamImpl mediaStream : mediaStreams) {
            if (mediaStream instanceof AudioMediaStreamImpl) {
                this.audioMediaStream = (AudioMediaStreamImpl) mediaStream;
            } else if (mediaStream instanceof VideoMediaStreamImpl) {
                this.videoMediaStream = (VideoMediaStreamImpl) mediaStream;
            } else if (mediaStream instanceof DataMediaStreamImpl) {
                this.dataMediaStream = (DataMediaStreamImpl) mediaStream;
            }
        }
    }

}
