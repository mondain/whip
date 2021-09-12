class WHIPClient {

    constructor() {
        //Offer SDP
        this.offer = null;
        //Ice properties
        this.iceUsername = null;
        this.icePassword = null;
        //Pending candidates
        this.candidates = [];
        this.endOfcandidates = false;
    }

    async publish(url, token, pc) {
        //If already publishing
        if (this.pc) {
            throw new Error("Already publishing")
        }
        //Store pc object
        this.pc = pc;
        //Store the endpoint url
        this.endpointUrl = url;
        //Store token
        this.token = token;
        //Listen for state change events
        pc.onconnectionstatechange = (event) => {
            console.log("Connection state: " + pc.connectionState);
            switch(pc.connectionState) {
                case "connected":
                    // The connection has become fully connected
                    break;
                case "disconnected":
                case "failed":
                    // One or more transports has terminated unexpectedly or in an error
                    this.stop();
                    break;
                case "closed":
                    // The connection has been closed
                    break;
            }
        };
        pc.onicegatheringstatechange = (event) => {
            let connection = event.target;
            switch(connection.iceGatheringState) {
                case "gathering":
                    console.log("Gathering");
                    break;
                case "complete":
                    console.log("Gathering complete");
                    // add end-of-candidates first, it gets pushed down in the munge
                    this.offer.sdp = this.offer.sdp.replace(/a=mid:0\r\n/g, 'a=mid:0\r\na=candidate:end-of-candidates\r\n');
                    this.offer.sdp = this.offer.sdp.replace(/a=mid:1\r\n/g, 'a=mid:1\r\na=candidate:end-of-candidates\r\n');
                    // add the candidates munged into the sdp
                    for (const candidate of this.candidates) {
                        console.log(candidate.candidate);
                        this.offer.sdp = this.offer.sdp.replace(/a=mid:0\r\n/g, 'a=mid:0\r\na=candidate:' + candidate.candidate.substring(10) + '\r\n');
                        this.offer.sdp = this.offer.sdp.replace(/a=mid:1\r\n/g, 'a=mid:1\r\na=candidate:' + candidate.candidate.substring(10) + '\r\n');
                    }
                    setTimeout(()=>this.sendOffer(),0);
                    break;
            }
        };
        //Listen for candidates
        pc.onicecandidate = (event) => {
            //console.log("onicecandidate: " + event);
            if (event.candidate)  {
                //console.log("Candidate: " + event.candidate);
                //Store candidate
                this.candidates.push(event.candidate);                                         
            } else {
                //No more candidates
                this.endOfcandidates = true;
            }
            //Schedule trickle on next tick
            //if (!this.iceTrickeTimeout) {
            //    this.iceTrickeTimeout = setTimeout(()=>this.trickle(),0);
            //}
        };
        //Create SDP offer
        this.offer = await pc.createOffer();
        console.log(this.offer.sdp);
        //Set it and keep the promise
        const sld = pc.setLocalDescription(this.offer);
        //Get the SDP answer
        //const answer = await this.fetched.text();
        //Wait until the offer was set locally
        await sld;
        //Schedule trickle on next tick
        //if (!this.iceTrickeTimeout) {
        //    this.iceTrickeTimeout = setTimeout(()=>this.trickle(),0);
        //}
    }

    async sendOffer() {
        //Do the post request to the WHIP endpoint with the SDP offer
        const fetched = await fetch(this.endpointUrl, {
            method: "POST",
            body: this.offer.sdp,
            headers:{
                "Authorization": "Bearer " + this.token, 
                "Content-Type": "application/sdp"
            }
        });
        //Get the resource url
        this.resourceURL = new URL(fetched.headers.get("location"), this.endpointUrl);
        console.log("Resource URL: " + this.resourceURL);
        //Get the SDP answer
        const answer = await fetched.text();
        //And set remote description
        const srd = await pc.setRemoteDescription({type:"answer",sdp: answer});
    }

    async trickle() {
        console.log("trickle");
        //Clear timeout
        this.iceTrickeTimeout = null;
        //Check if there is any pending data
        if (!this.candidates.length || !this.endOfcandidates || !this.resourceURL)
            //Do nothing
            return;
        try {
            //Get local ice properties
            const local = this.pc.getTransceivers()[0].sender.transport.iceTransport.getLocalParameters();
            //Get them for transport
            this.iceUsername = local.usernameFragment;
            this.icePassword = local.password;
        } catch (e) {
            //Fallback for browsers not supporting ice transport
            this.iceUsername = this.offer.sdp.match(/a=ice-ufrag:(.*)\r\n/)[1];
            this.icePassword = this.offer.sdp.match(/a=ice-pwd:(.*)\r\n/)[1];
        }
        //Prepare fragment
        let fragment = 
            "a=ice-ufrag:" + this.iceUsername + "\r\n" +
            "a=ice-pwd:" + this.icePassword + "\r\n";
        //Get peerconnection transceivers
        const transceivers = this.pc.getTransceivers();
        //Get medias
        const medias = {};
        //For each candidate
        for (const candidate of this.candidates) {
            //Get mid for candidate
            const mid = candidate.sdpMid
            //Get associated transceiver
            const transceiver = transceivers.find(t=>t.mid==mid);
            //Get media
            let media = medias[mid];
            //If not found yet
            if (!media)
                //Create media object
                media = medias[mid] = {
                    mid,
                    kind : transceiver.receiver.track.kind,
                    candidates: [],
                };
            //Add candidate
            media.candidates.push(candidate);
        }
        //For each media
        for (const media of Object.values(medias)) {
            //Add media to fragment
            fragment += 
                "m="+ media.kind + " RTP/AVP 0\r\n" +
                "a=mid:"+ media.mid + "\r\n";
            //Add candidate
            for (const candidate of media.candidates)
                fragment += "a=" + candidate.candidate + "\r\n";
            if (this.endOfcandidates)
                fragment += "a=end-of-candiadates\r\n";
        }
        //Clean pending data
        this.candidates = [];
        this.endOfcandidates = false;
        //Do the post request to the WHIP resource
        await fetch(this.resourceURL, {
            method: "PATCH",
            body: fragment,
            headers:{
                "Content-Type": "application/trickle-ice-sdpfrag"
            }
        });
    }

    async stop() {
        //Cancel any pending timeout
        this.iceTrickeTimeout = clearTimeout(this.iceTrickeTimeout);
        //If we don't have the resource url
        if (this.resourceURL) {
            //Send a delete
            await fetch(this.resourceURL, {
                method: "DELETE",
            });
        } else {
            console.error("WHIP resource url not available at stop");
            //throw new Error("WHIP resource url not available yet");
        }
        //Close peerconnection
        this.pc.close();
        //Null
        this.pc = null;
    }
}