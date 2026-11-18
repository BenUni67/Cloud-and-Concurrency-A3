package comp3011;

// A subtitle and how long it appears from (start and end time microseconds)
// Video player already uses microseconds
public record SubtitleInstance(long startUs, long endUs, String text) {

    public boolean isVisibleAt(long timestampUs) {
        return timestampUs >= startUs && timestampUs < endUs;
    }
}