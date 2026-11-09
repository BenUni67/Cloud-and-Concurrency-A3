const startButton = document.getElementById("startButton");
const stopButton = document.getElementById("stopButton");
const voiceStatus = document.getElementById("voiceStatus");
const audioPlayback = document.getElementById("audioPlayback");
const transcriptionText = document.getElementById("transcriptionText");
const inputTokens = document.getElementById("inputTokens");
const outputTokens = document.getElementById("outputTokens");
const totalTokens =document.getElementById("totalTokens");
const transcriptionDetails =document.getElementById("transcriptionDetails");
	
// https://stackoverflow.com/questions/34820578/how-to-capture-audio-in-javascript

let mediaRecorder;
let mediaStream;
let audioChunks = [];

startButton.addEventListener("click", async () => {
	
	try {
		// This is asking the browser to give the website access to the user's mic
		// Returns a MediaStream object which is basically just the mic audio
		voiceStatus.classList.remove("failed");
				
		// Clear previous transcription
		transcriptionText.textContent = "";
		transcriptionDetails.open = false;

		// Clear the old token values
		inputTokens.textContent = "-";
		outputTokens.textContent = "-";
		totalTokens.textContent = "-";
		
		// Requests microphone access, then returns a MediaStream which is a live audio feed from the mic
		mediaStream = await navigator.mediaDevices.getUserMedia({audio: true});

		// If browser allows then encode audio using WebM with the opus codec, otherwise use browser default	
		// Opus WebM is a high quality small file audio recording that's free and built into browser	
		if (MediaRecorder.isTypeSupported("audio/webm;codecs=opus")) {
			mediaRecorder = new MediaRecorder(mediaStream, {mimeType: "audio/webm;codecs=opus", audioBitsPerSecond: 64000});
		} else {
			mediaRecorder = new MediaRecorder(mediaStream);
		}
		
		audioChunks = [];
		
		// event.data is the recorded audio, which is stored as audio chunks
		mediaRecorder.addEventListener("dataavailable", event => {
			if (event.data.size > 0) {
				audioChunks.push(event.data)
			}
		})
		
		// When stop is hit, the audio chunks are formed into a an audioBlob, which stores audio in the browsers memory
		mediaRecorder.addEventListener("stop", async () => {

	        const audio = new Blob(audioChunks, {type: mediaRecorder.mimeType});
			// Allows for playback of the audio recording on the site
	        audioPlayback.src = URL.createObjectURL(audio);;
	        voiceStatus.textContent ="Recording complete";
			// Used to change the colour of the voice status text
			voiceStatus.classList.remove("recording");
			voiceStatus.classList.add("complete");
	
			// Upload the audio for transcription
			await uploadRecording(audio);
			
			// Stops the microphone from recording, basically ensures that it's off		
	        mediaStream.getTracks().forEach(track => track.stop());
	        startButton.disabled = false;
	        stopButton.disabled = true;
		});
		
		// Begins the recording
		mediaRecorder.start();
		// Start button is disabled, forces user to click stop before starting another recording
		startButton.disabled = true;
		stopButton.disabled = false;
		voiceStatus.classList.remove("complete");
		voiceStatus.classList.add("recording");
		voiceStatus.textContent = "Recording..."
	} catch (error) {
		console.error(error);
		voiceStatus.classList.add("failed");
		voiceStatus.textContent = "Could not access microphone, please try again";
	}
});

stopButton.addEventListener("click", () => {
	if (mediaRecorder && mediaRecorder.state === "recording") {
		mediaRecorder.stop();
		voiceStatus.textContent = "Processing";
	}
});

async function uploadRecording(audio) {
	try {
		// Basically constructing a multipart request that contains a name, filename and contents
		const formData = new FormData();
		formData.append("file", audio, "recording.webm");
		
		const response = await fetch("/api/v1/transcriptions", {method:"POST", body: formData});
		if (!response.ok) {
			throw new Error("Server sent" + response.status);
		}
		
		// returns a json response
		const result = await response.json();
		voiceStatus.textContent = "Transcription Received";
		inputTokens.textContent = result.inputTokens;
		outputTokens.textContent = result.outputTokens;
		totalTokens.textContent = result.totalTokens;
		transcriptionText.textContent = result.text;
	} catch (error) {
		console.error(error);
		voiceStatus.textContent = "Failed to upload recording";
	}
}