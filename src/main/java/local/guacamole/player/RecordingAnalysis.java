package local.guacamole.player;

import java.util.List;

public record RecordingAnalysis(
        long duration,
        int instructionCount,
        int keyCount,
        List<ActivityBucket> buckets,
        List<RecordingKeyEvent> keyEvents,
        List<RecordingClipboardEvent> clipboardEvents) {
}
