/* Based on code created by Sergio Garcia Murillo, modified by Paul Gregoire to be used with Red5 Pro. */
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

	// candidates are in-line to the SDP, no trickle used
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
        };
        //Create SDP offer
        this.offer = await pc.createOffer();
        console.log(this.offer.sdp);
        //Set it and keep the promise
        const sld = pc.setLocalDescription(this.offer);
        //Wait until the offer is set locally
        await sld;
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
        //Get the SDP answer
        const answer = await fetched.text();
        //And set remote description
        pc.setRemoteDescription({type:"answer",sdp: answer});
        //Get the resource url
        this.resourceURL = new URL(fetched.headers.get("location"), this.endpointUrl);
        console.log("Resource URL: " + this.resourceURL);
    }

    async stop() {
		if (!this.pc) {
			throw new Error("Already stopped");
		}
        //If we don't have the resource url
        if (this.resourceURL) {
            //Send a delete
            await fetch(this.resourceURL, {
                method: "DELETE",
            });
        } else {
            console.error("WHIP resource url not available at stop");
        }
        //Close peerconnection
        this.pc.close();
        //Null
        this.pc = null;
    }
}