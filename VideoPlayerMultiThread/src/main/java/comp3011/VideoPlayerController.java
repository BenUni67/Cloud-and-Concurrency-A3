/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. <student name and student number insert here upon modification>
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import java.util.HashSet;
import java.util.Set;
import java.util.Comparator;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/**
 * The controller part of the video player model-view-controller architecture.
 * 
 * <p>
 * Wires up events from the {@link VideoPlayerView} to the {@link VideoPlayerModel}.
 * This includes button actions, keyboard shortcuts and display resizing.
 * </p>
 */
public class VideoPlayerController {
    private final VideoPlayerModel model;
    private final VideoPlayerView view;
    private final SubtitleClient subtitleClient = new SubtitleClient();
    private final List<SubtitleInstance> loadedSubtitles = new ArrayList<>();
    private final Set<SubtitleInstance> seenSubtitles = new HashSet<>();
    private File subtitleVideoFile;
    private long subtitleRequestId = 0;
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;

    public VideoPlayerController(
            boolean audioEnabled,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            List<FrameProcessor> frameProcessors, boolean subtitlesInitiallyEnabled) {
        this.view = new VideoPlayerView();
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        model = new VideoPlayerModel(
                audioEnabled,
                this::onVideoSizeChanged,
                this::onFrameReady,
                this::onSubtitleTextChanged,
                this::onStatusChanged,
                this::onPlaybackStateChanged,
                this::onAudioOutputStateChanged,
                frameProcessors,
                subtitlesInitiallyEnabled);

        view.getStartOverButton().setOnAction(_ -> startOver());
        view.getBackFiveButton().setOnAction(_ -> backFiveSeconds());
        view.getPausePlayButton().setOnAction(_ -> togglePause());
        view.getForwardFiveButton().setOnAction(_ -> forwardFiveSeconds());
        view.getStopButton().setOnAction(_ -> stopAndExit());
        view.getAudioButton().setOnAction(_ -> toggleAudioOutput());
        view.getSubtitleButton().setOnAction(_ -> toggleSubtitles());
        view.showSubtitlesEnabled(model.isSubtitlesEnabled());        
        view.showAudioEnabled(model.isAudioOutputEnabled());
    }

    public void installKeyboardShortcuts(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                stopAndExit();
                event.consume();
            } else if (event.getCode() == KeyCode.SPACE) {
                togglePause();
                event.consume();
            } else if (event.getCode() == KeyCode.LEFT) {
                backFiveSeconds();
                event.consume();
            } else if (event.getCode() == KeyCode.RIGHT) {
                forwardFiveSeconds();
                event.consume();
            } else if (event.getCode() == KeyCode.HOME) {
                startOver();
                event.consume();
            } else if (event.getCode() == KeyCode.A) {
                toggleAudioOutput();
                event.consume();
            } else if (event.getCode() == KeyCode.S) {
            	toggleSubtitles();
            	event.consume();
            }
        });
    }

    public VideoPlayerView getView() {
        return view;
    }

    public void play(File file) {
        subtitleVideoFile = file;
        loadedSubtitles.clear();
        seenSubtitles.clear();

        view.setPlaybackControlsDisabled(false);
        model.play(file);
        requestSubtitlesAt(0);
    }

    public void shutdown() {
    	subtitleRequestId++;
    	subtitleClient.close();
        model.shutdown();
    }

    private void startOver() {
        model.startOver();
        requestSubtitlesAt(model.getCurrentTimestampUs());
    }

    private void backFiveSeconds() {
        model.backFiveSeconds();
        requestSubtitlesAt(model.getCurrentTimestampUs());
    }

    private void togglePause() {
        model.togglePause();
    }

    private void forwardFiveSeconds() {
        model.forwardFiveSeconds();
        requestSubtitlesAt(model.getCurrentTimestampUs());
    }

    private void toggleAudioOutput() {
        model.toggleAudioOutput();
    }

    private void toggleSubtitles() {
    	boolean enabled = model.toggleSubtitles();
    	view.showSubtitlesEnabled(enabled);
    }
    
    private void onSubtitleTextChanged(String text) {
    	view.showSubtitleText(text);
    }
    
    private void stopAndExit() {
        model.stopPlayback();
        javafx.application.Platform.exit();
    }

    private void onVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }

    private void onFrameReady(Image image) {
        view.setImage(image);
    }

    private void onStatusChanged(String status) {
        view.setStatus(status);
        boolean failed = status.startsWith("Error:");
        boolean stopped = status.equals("Stopped");
        boolean finished = status.equals("Playback finished");

        if (failed || stopped || finished) {
            subtitleRequestId++;
            subtitleClient.cancel();
            if (failed || stopped) {
                // prevent further requests for the unavailable video
                subtitleVideoFile = null;
                loadedSubtitles.clear();
                seenSubtitles.clear();
                model.setSubtitleInstances(List.of());
                view.showSubtitleText("");
            }
        }
    }

    private void onPlaybackStateChanged(boolean playing, boolean paused) {
        if (playing && !paused) {
            view.showPauseAction();
        } else {
            view.showPlayAction();
        }
    }

    private void onAudioOutputStateChanged(boolean audioOutputEnabled) {
        view.showAudioEnabled(audioOutputEnabled);
    }
    
    private void requestSubtitlesAt(long positionUs) {
        if (subtitleVideoFile == null) {
            return;
        }

        // each new request invalidates callbacks from an old request
        long requestId = ++subtitleRequestId;

        subtitleClient.startAt(subtitleVideoFile, positionUs, instances -> Platform.runLater(() -> {

        	// ignore results from a previous seek or video
            if (requestId != subtitleRequestId) {
                return;
            }

            boolean changed = false;

            // keep the previously loaded subtitles and avoid any dupliate
            for (SubtitleInstance instance : instances) {
                if (seenSubtitles.add(instance)) {
                    loadedSubtitles.add(instance);
                    changed = true;
                }
            }

            if (changed) {
                loadedSubtitles.sort(Comparator.comparingLong(SubtitleInstance::startUs));
                model.setSubtitleInstances(loadedSubtitles);
            }
        }), error -> Platform.runLater(() -> {
            if (requestId == subtitleRequestId) {
                System.err.println("Subtitle error: " + error);
            }
        }));
    }
}
