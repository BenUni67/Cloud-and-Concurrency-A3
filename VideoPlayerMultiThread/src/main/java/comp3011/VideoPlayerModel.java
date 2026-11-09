/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Benjamin Rebbeck a1924805
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.image.Image;

/**
 * The model part of the video player model-view-controller architecture.
 *
 * <p>
 * Owns the FFmpeg decoder, playback clock, seeking logic, audio scheduling,
 * and frame processing - which it all manages through cooperative multi-tasking
 * on a single thread. It publishes decoded frames and playback state through
 * callbacks supplied by its owning {@link VideoPlayerController}, keeping it
 * decoupled from the JavaFX view.
 * </p>
 */
public class VideoPlayerModel {
    private static final long NO_SEEK_REQUEST = -1; // Sentinel value used when no seek position is active.
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;

    private final List<FrameProcessor> frameProcessors;
    // concurrent hashmap to associate each worker thread with its own list of effects
    private Map<Thread, List<FrameProcessor>> workerPipelines = new ConcurrentHashMap<>();
    private InfoVideo pipelineVideoInfo;
    
   
    // Runtime.getRuntime().avaliableProcessors() determines how many processors Java has available
    // Math.max(1, etc) will ensure that one worked will always be available even only if one processor is available
    private final ExecutorService frameExecutor = Executors.newFixedThreadPool(
    		Math.max(1, Runtime.getRuntime().availableProcessors() -1));
    
    // limits how many frames can be processed at once
    // assistance by AI for this
    private static final int MAX_IN_FLIGHT = Math.max(1, Math.min(6, Runtime.getRuntime().availableProcessors() - 1));

    // stores processed frames until they can be displayed in the correct order
    private final Map<Long, PreparedFrame> completedFrames = new HashMap<>();

    private long nextFrameToSubmit = 0;
    private long nextFrameToDisplay = 0;
    private boolean decoderFinished = false;
    private boolean showPaused = false;
    
    // allows us to identify frames that aren't relevant anymore
    private long playbackGeneration = 0;
    private final Queue<PendingAudio> pendingAudio = new ArrayDeque<>();
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;

    // This is the critical wiring that allows the framework (JavaFX) to call
    // into our model logic every time it goes around its event loop. Every GUI
    // system has an event loop so that button clicks, key presses and window
    // resizes can be responded to. Most offer ways of running additional logic
    // in either idle time (when there are no pending UI events to process) or
    // on a regular heart beat, such as with this JavaFX AnimationTimer. Here
    // we just create a little anonymous local subclass and override the handle
    // method to call the method we want run every heart beat.
    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pumpPlayback(now);
        }
    };

    private File videoFile; // Path to the media that comes from the command line
    private FFmpegFrameGrabber grabber; // This is the 3rd party video media decoder. It deals in JavaCV Frame objects.
    private JavaFXFrameConverter converter; // Takes Frame objects to JavaFX Image objects, which can be put on screen.
    private AudioPlayer audioPlayer; // This is ours. It has some real time buffering smarts.
    private PreparedFrame preparedFrame; // Ours, but is just an Image with some meta-data added.
    private boolean prepareNextFrameQueued; // True only when the event loop needs to call our prepareNextFrame method.
    private boolean playbackOpen; // True when playback is happening.
    private boolean pauseRequested;
    private boolean audioOutputEnabled;
    private boolean audioAvailable;
    private boolean frameProcessorsInitialised;
    private long currentTimestampUs;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private double frameRate;
    private int intFrameRate;
    private int totalVideoFrames;
    private long firstTimestampUs = NO_SEEK_REQUEST;
    private long logicalPlaybackBaseUs;
    private long playbackStartNs;
    private long pauseStartedNs;
    private long relativeSeekBaseUs = NO_SEEK_REQUEST;

    public VideoPlayerModel(
            boolean audioEnabled,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler,
            List<FrameProcessor> frameProcessors) {
        audioOutputEnabled = audioEnabled;
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
        this.frameProcessors = new ArrayList<>(frameProcessors);
        
        // gives each effect type a unique index
        // this code was written with the assistance of AI, as per what is allowed in the assignment
        // If command line argument was -ssfn
        // the resulting indices would be, FrameScratcher = 0, FrameScratcher = 1, FrameFlickerer = 0, FrameNumberer = 0
        // HashMap is used to count how many instances we've encountered of each effect type
        Map<Class<?>, Integer> instanceCounts = new HashMap<>();
        
        for (FrameProcessor processor : this.frameProcessors) {
        	Class<?> type = processor.getClass();
        	int index = instanceCounts.getOrDefault(type, 0);
        	processor.setInstanceIndex(index);
        	instanceCounts.put(type, index + 1);
        }
    }

    public void play(File file) {
        videoFile = file;
        videoDurationUs = NO_SEEK_REQUEST;
        videoFrameDurationUs = 0;
        startPlayback(0, false, NO_SEEK_REQUEST);
    }

    public void startOver() {
        if (videoFile == null) {
            return;
        }

        seekTo(0);
    }

    public void backFiveSeconds() {
        seekRelative(-FIVE_SECONDS_US);
    }

    public void forwardFiveSeconds() {
        seekRelative(FIVE_SECONDS_US);
    }

    public void togglePause() {
        if (!playbackOpen) {
            if (videoFile != null) {
                startPlayback(displayableSeekTimestamp(currentTimestampUs), false, currentTimestampUs);
            }
            return;
        }

        pauseRequested = !pauseRequested;
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        if (pauseRequested) {
            pauseStartedNs = System.nanoTime();
            flushAudioOutput();
        } else {
            resumePlaybackClock(System.nanoTime());
        }

        notifyPlaybackStateChanged();
    }

    public void toggleAudioOutput() {
        audioOutputEnabled = !audioOutputEnabled;
        pendingAudio.clear();
        flushAudioOutput();
        notifyAudioOutputStateChanged();
    }

    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }

    public void stopPlayback() {
        closePlaybackResources();
        currentTimestampUs = 0;
        pauseRequested = false;
        notifyStatusChanged("Stopped");
        notifyFrameReady(null);
        notifyPlaybackStateChanged();
    }

    public void shutdown() {
        closePlaybackResources();
        // requests cancellation of running tasks, and will shut down pool when app exits.
        // using shutdown() instead of shutdownnow() allows for submitted tasks to finish and release frames
        frameExecutor.shutdown();
    }

    private void seekRelative(long offsetUs) {
        if (videoFile == null) {
            return;
        }

        long baseTimestampUs = relativeSeekBaseUs != NO_SEEK_REQUEST
                ? relativeSeekBaseUs
                : currentTimestampUs;
        seekTo(baseTimestampUs + offsetUs);
    }

    private void seekTo(long timestampUs) {
        long logicalTimestampUs = clampSeekTimestamp(timestampUs);
        long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);

        if (!playbackOpen) {
            startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
            return;
        }

        try {
            grabber.setTimestamp(grabTimestampUs);
            resetPlaybackClock(logicalTimestampUs);
            flushAudioOutput();
            prepareNextFrame();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void startPlayback(long startTimestampUs, boolean initiallyPaused, long initialRelativeSeekBaseUs) {
        closePlaybackResources();

        pauseRequested = initiallyPaused;
        currentTimestampUs = initialRelativeSeekBaseUs != NO_SEEK_REQUEST
                ? initialRelativeSeekBaseUs
                : startTimestampUs;
        relativeSeekBaseUs = initialRelativeSeekBaseUs;

        notifyStatusChanged(videoFile.getName());

        try {
            openPlaybackResources(startTimestampUs);
            resetPlaybackClock(currentTimestampUs);
            playbackOpen = true;
            playbackTimer.start();
            prepareNextFrame();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void openPlaybackResources(long startTimestampUs) throws Exception {
        converter = new JavaFXFrameConverter();
        grabber = new FFmpegFrameGrabber(videoFile);
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.start();

        notifyVideoSizeChanged(grabber.getImageWidth(), grabber.getImageHeight());

        frameRate = grabber.getFrameRate();
        intFrameRate = (int) Math.round(frameRate);
        videoFrameDurationUs = frameRate > 0
                ? (int) Math.round(1_000_000.0 / frameRate)
                : 0;
        totalVideoFrames = grabber.getLengthInVideoFrames();

        long durationUs = grabber.getLengthInTime();
        videoDurationUs = durationUs > 0
                ? durationUs
                : NO_SEEK_REQUEST;

        audioAvailable = grabber.hasAudio();
        audioPlayer = new AudioPlayer();
        if (audioAvailable) {
            audioPlayer.open(grabber.getSampleRate(), grabber.getAudioChannels());
        }

        if (startTimestampUs > 0) {
            grabber.setTimestamp(startTimestampUs);
        }
    }

    private void resetPlaybackClock(long logicalTimestampUs) {
        playbackGeneration++;
    	pendingAudio.clear();
        preparedFrame = null;
        currentTimestampUs = logicalTimestampUs;
        relativeSeekBaseUs = logicalTimestampUs;
        firstTimestampUs = NO_SEEK_REQUEST;
        logicalPlaybackBaseUs = logicalTimestampUs;
        playbackStartNs = 0;
        pauseStartedNs = pauseRequested ? System.nanoTime() : 0;
        frameProcessorsInitialised = false;
        workerPipelines = new ConcurrentHashMap<>();
        pipelineVideoInfo = null;    
        nextFrameToSubmit = 0;
        nextFrameToDisplay = 0;
        decoderFinished = false;
        completedFrames.clear();
        prepareNextFrameQueued = false;
        showPaused = pauseRequested;
        }

    private void resumePlaybackClock(long now) {
        if (pauseStartedNs > 0 && playbackStartNs > 0) {
            playbackStartNs += now - pauseStartedNs;
        }
        pauseStartedNs = 0;
    }

    private void prepareNextFrame() {

        // prevents too many frames from being processed at the same time
        if (!playbackOpen || decoderFinished || nextFrameToSubmit - nextFrameToDisplay >= MAX_IN_FLIGHT) {
            return;
        }

        try {
            FrameWork nextFrame = readNextVideoFrame();

            if (nextFrame == null) {
                return;
            }

            // give every frame a sequence number for display order
            long sequence = nextFrameToSubmit++;
            long generation = playbackGeneration;

            // capture the processing pipeline belonging to this play back
            Map<Thread, List<FrameProcessor>> pipelinesForTask = workerPipelines;
            InfoVideo videoInfoForTask = pipelineVideoInfo;

            CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
                try {

                    // each worker thread uses its own processor 
                    List<FrameProcessor> processors = pipelinesForTask.computeIfAbsent(
                            Thread.currentThread(),
                            thread -> {
                                try {
                                    return FrameProcessorObject.createPipeline(frameProcessors, videoInfoForTask);
                                } catch (Exception e) {
                                    throw new CompletionException(e);
                                }
                            }
                    );

                    processFrame(nextFrame.frame(), nextFrame.info(), processors);

                } catch (Exception e) {
                    throw new CompletionException(e);
                }

            }, frameExecutor);

            // return to the java fx thread once processing finish
            task.whenComplete((result, error) -> Platform.runLater(() -> {

                try (Frame frame = nextFrame.frame()) {
                    // ignore results from an older play back
                    if (!playbackOpen || generation != playbackGeneration) {
                        return;
                    }

                    if (error != null) {
                        handlePlaybackError(new Exception("Frame processing failed", error));
                        return;
                    }

                    PreparedFrame completed = convertProcessedFrame(
                            frame,
                            nextFrame.info(),
                            nextFrame.logicalTimestampUs()
                    );

                    // store the frame using its original sequence number
                    completedFrames.put(sequence, completed);

                } catch (Exception e) {
                    handlePlaybackError(e);
                }
            }));

        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }
    
    // changed to read a FrameWork object
    private FrameWork readNextVideoFrame() throws Exception {
        while (playbackOpen) {
            Frame frame = grabFrame();
            if (frame == null) {
                decoderFinished = true;
                return null;
            }

            long timestampUs = grabber.getTimestamp();
            if (audioAvailable && frame.samples != null) {
                queueAudio(timestampUs, frame);
            }

            if (frame.image == null) {
                continue;
            }

            if (!frameProcessorsInitialised) {
                initialiseFrameProcessors(new InfoVideo(
                        mediaName(videoFile),
                        totalVideoFrames,
                        frame.imageWidth,
                        frame.imageHeight,
                        frame.imageDepth,
                        frame.imageChannels,
                        frame.imageStride,
                        frameRate,
                        intFrameRate,
                        videoFrameDurationUs,
                        grabber.getPixelFormat()));
                frameProcessorsInitialised = true;
            }

            if (firstTimestampUs == NO_SEEK_REQUEST) {
                firstTimestampUs = timestampUs;
                playbackStartNs = System.nanoTime();
                if (pauseRequested) {
                    pauseStartedNs = playbackStartNs;
                }
            }

            int frameNumber = grabber.getFrameNumber();
            InfoFrame info = new InfoFrame(frameNumber, timestampUs);
            
            long relativeTimestampUs = Math.max(0,  timestampUs - firstTimestampUs);
            long logicalTimestampUs = logicalPlaybackBaseUs + relativeTimestampUs;
            
            // no effects or conversion are currently applied in this section now
            
            // creating an a copy so other threads can process the frame safely
            // the decode may reuse memory when decoding subsequent frames
            Frame frameCopy = frame.clone();
            return new FrameWork(frameCopy, info, logicalTimestampUs);
        }

        return null;
    }
    
    // Will convert a processed frame into an image to display it
    private PreparedFrame convertProcessedFrame(
    		Frame frame,
    		InfoFrame info,
    		long logicalTimestampUs) throws Exception {
    	
    		// Converts frame to image
    		Image image = converter.convert(frame);
    		
    		return new PreparedFrame(
    				image,
    				info.frameNumber(),
    				info.timestampUs(),
		            logicalTimestampUs,
	            	System.nanoTime());
    }

    // This is called on a heart beat by the GUI thread. We want to be co-operative here by (a) not blocking, and (b)
    // getting our required work out of the way quickly so that we can return control flow to the JavaFX event loop for
    // handling user interaction with the GUI and rendering. We have three jobs: (1) keep audio flowing if sound is on,
    // (2) display a frame if it is due and (3) prepare the next frame if we're in the window after the previous frame
    // has gone to the display. We don't buffer frames here, just handling them one at a time. Not a great architecture,
    // living on the edge a bit, but can't do much better on a single thread.
    private void pumpPlayback(long now) {
        if (!playbackOpen) {
            return;
        }

        // Task (1)
        writeDueAudio(now);

        // get the next completed frame, but only in correct order
        if (preparedFrame == null) {
        	preparedFrame = completedFrames.remove(nextFrameToDisplay);
        }

        // display the frames on time, or show one preview frame after a pause
        if (preparedFrame != null && ((!pauseRequested && frameTargetTimeNs(preparedFrame.logicalTimestampUs()) <= now) || (pauseRequested && showPaused))) {
            displayPreparedFrame(now);
            nextFrameToDisplay++;
            showPaused = false;
        }
        
        // keep submitting frames while capacity is available
        if (!pauseRequested && !decoderFinished && nextFrameToSubmit - nextFrameToDisplay < MAX_IN_FLIGHT && !prepareNextFrameQueued) {

            prepareNextFrameQueued = true;
            long generation = playbackGeneration;

            Platform.runLater(() -> {
            	// ignore callback to an older play back
            	if (!playbackOpen || generation != playbackGeneration) {
            		return;
            	}
            	
                prepareNextFrameQueued = false;

                if (!pauseRequested) {
                	prepareNextFrame();
                }
            });
        }

        // only finish after all submitted frames have been displayed
        if (!pauseRequested && decoderFinished && nextFrameToDisplay == nextFrameToSubmit && pendingAudio.isEmpty()) {
            finishPlayback();
        }
    }

    private void displayPreparedFrame(long now) {
        PreparedFrame frame = preparedFrame;
        preparedFrame = null;
        currentTimestampUs = frame.logicalTimestampUs();
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        // Dump some logging to the console once per second so that real time performance can be monitored.
        if (intFrameRate > 0 && frame.frameNumber() % intFrameRate == 0) {
        	long remainingUs = (frameTargetTimeNs(frame.logicalTimestampUs()) - frame.preparedAtNs()) / 1_000L;            
        	if (remainingUs < 0) {
                System.out.printf("Frame headroom is \u001B[31m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            } else {
                System.out.printf("Frame headroom is \u001B[32m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            }
        }

        notifyFrameReady(frame.image());
    }

    private void queueAudio(long timestampUs, Frame frame) {
        if (!audioOutputEnabled || audioPlayer == null) {
            return;
        }

        byte[] samples = audioPlayer.copySamples(frame);
        if (samples.length > 0) {
            pendingAudio.add(new PendingAudio(timestampUs, samples));
        }
    }

    private void writeDueAudio(long now) {
        // Handle some obvious early exits
        if (!audioOutputEnabled || audioPlayer == null) {
            pendingAudio.clear();
            return;
        }
        if (pauseRequested || firstTimestampUs == NO_SEEK_REQUEST || playbackStartNs <= 0) {
            return;
        }

        // Here is the real time dependent logic
        long dueTimestampUs = firstTimestampUs + (now + AUDIO_LEAD_NS - playbackStartNs) / 1_000L;
        while (!pendingAudio.isEmpty()) {
            PendingAudio audio = pendingAudio.peek();
            if (audio.timestampUs() > dueTimestampUs) {
                return;
            }

            int written = audioPlayer.write(audio.samples(), audio.offset(), audio.remaining());
            if (written <= 0) {
                return;
            }

            audio.advance(written);
            if (!audio.finished()) {
                return;
            }
            pendingAudio.remove();
        }
    }

    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST
                ? videoDurationUs
                : currentTimestampUs;

        closePlaybackResources();
        pauseRequested = true;
        currentTimestampUs = endTimestampUs;
        relativeSeekBaseUs = endTimestampUs;
        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }

    private void handlePlaybackError(Exception e) {
        e.printStackTrace();
        closePlaybackResources();
        pauseRequested = false;
        notifyStatusChanged("Error: " + e.getMessage());
        notifyPlaybackStateChanged();
    }

    private void closePlaybackResources() {
    	playbackGeneration++;
        playbackTimer.stop();
        playbackOpen = false;
        preparedFrame = null;
        pendingAudio.clear();
        firstTimestampUs = NO_SEEK_REQUEST;
        playbackStartNs = 0;
        pauseStartedNs = 0;
        audioAvailable = false;
        frameProcessorsInitialised = false;
        workerPipelines = new ConcurrentHashMap<>();
        pipelineVideoInfo = null;
        nextFrameToSubmit = 0;
        nextFrameToDisplay = 0;
        decoderFinished = false;
        completedFrames.clear();
        prepareNextFrameQueued = false;
        showPaused = false;
        
        if (audioPlayer != null) {
            audioPlayer.close();
            audioPlayer = null;
        }

        if (grabber != null) {
            try {
                grabber.stop();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            try {
                grabber.close();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            grabber = null;
        }

        if (converter != null) {
            converter.close();
            converter = null;
        }
    }

    private String mediaName(File file) {
        String fileName = file.getName();
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0) {
            return fileName;
        }
        return fileName.substring(0, extensionStart);
    }

    private long clampSeekTimestamp(long timestampUs) {
        long clampedTimestampUs = Math.max(0, timestampUs);
        long durationUs = videoDurationUs;
        if (durationUs != NO_SEEK_REQUEST) {
            clampedTimestampUs = Math.min(clampedTimestampUs, durationUs);
        }
        return clampedTimestampUs;
    }

    private long displayableSeekTimestamp(long logicalTimestampUs) {
        long durationUs = videoDurationUs;
        if (durationUs == NO_SEEK_REQUEST || logicalTimestampUs < durationUs) {
            return logicalTimestampUs;
        }

        int frameDurationUs = videoFrameDurationUs;
        if (frameDurationUs <= 0) {
            return Math.max(0, durationUs - 1);
        }

        return Math.max(0, durationUs - frameDurationUs);
    }
    
    // calculates a frames current display time using playback clock
    private long frameTargetTimeNs(long logicalTimestampUs) {
    	return playbackStartNs + Math.max(0, logicalTimestampUs - logicalPlaybackBaseUs) * 1_000L; 
    }

    private void processFrame(Frame frame, InfoFrame info, List<FrameProcessor> processors) throws Exception {
		// apply the effects of the worker in the correct order
		for (FrameProcessor processor : processors) {
			processor.process(frame, info);
        }
    } 
    
    private void initialiseFrameProcessors(InfoVideo videoInfo) throws Exception {
    	// starts a fresh set of worker pipelines for the play back
    	pipelineVideoInfo = videoInfo;
    	workerPipelines = new ConcurrentHashMap<>();
    }

    private Frame grabFrame() throws Exception {
        if (audioAvailable) {
            return grabber.grab();
        }
        return grabber.grabImage();
    }

    private void flushAudioOutput() {
        if (audioPlayer != null) {
            audioPlayer.flush();
        }
    }

    private void notifyVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }

    private void notifyFrameReady(Image image) {
        frameReadyHandler.accept(image);
    }

    private void notifyStatusChanged(String status) {
        statusChangedHandler.accept(status);
    }

    private void notifyPlaybackStateChanged() {
        playbackStateChangedHandler.accept(playbackOpen, pauseRequested);
    }

    private void notifyAudioOutputStateChanged() {
        audioOutputStateChangedHandler.accept(audioOutputEnabled);
    }

    private record PreparedFrame(
            Image image,
            int frameNumber,
            long mediaTimestampUs,
            long logicalTimestampUs,
            long preparedAtNs) {
    }
    
    // stores the frame data and timing for independant processing
    private record FrameWork(
    		Frame frame,
    		InfoFrame info,
    		long logicalTimestampUs) {
    }
    	

    private static class PendingAudio {
        private final long timestampUs;
        private final byte[] samples;
        private int offset;

        PendingAudio(long timestampUs, byte[] samples) {
            this.timestampUs = timestampUs;
            this.samples = samples;
        }

        long timestampUs() {
            return timestampUs;
        }

        byte[] samples() {
            return samples;
        }

        int offset() {
            return offset;
        }

        int remaining() {
            return samples.length - offset;
        }

        void advance(int byteCount) {
            offset += byteCount;
        }

        boolean finished() {
            return offset >= samples.length;
        }
    }
}
