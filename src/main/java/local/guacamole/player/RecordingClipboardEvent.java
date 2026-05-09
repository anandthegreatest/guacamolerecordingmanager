package local.guacamole.player;

public record RecordingClipboardEvent(long timestamp, String mimetype, String text, int length, boolean truncated) {
}
