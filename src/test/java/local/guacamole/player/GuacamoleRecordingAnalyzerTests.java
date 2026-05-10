package local.guacamole.player;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuacamoleRecordingAnalyzerTests {

    private final GuacamoleRecordingAnalyzer analyzer = new GuacamoleRecordingAnalyzer();

    @TempDir
    Path tempDir;

    @Test
    void readsDirectGuacdKeyEventsUsingCurrentSyncTimestamp() throws IOException {
        Path recording = recording(
                instruction("sync", "1000"),
                instruction("key", "97", "1"),
                instruction("key", "97", "0"),
                instruction("sync", "1250"),
                instruction("key", "65293", "1"),
                instruction("sync", "1500"));

        RecordingAnalysis analysis = analyzer.analyze(recording);

        assertThat(analysis.keyCount()).isEqualTo(3);
        assertThat(analysis.keyEvents())
                .extracting(RecordingKeyEvent::timestamp, RecordingKeyEvent::keysym, RecordingKeyEvent::pressed)
                .containsExactly(
                        tuple(0L, 97, true),
                        tuple(0L, 97, false),
                        tuple(250L, 65293, true));
    }

    @Test
    void readsApplicationRecordingKeyEventsUsingEmbeddedTimestamp() throws IOException {
        Path recording = recording(
                instruction("sync", "1000"),
                instruction("sync", "1100"),
                instruction("key", "98", "1", "1250"),
                instruction("sync", "1500"));

        RecordingAnalysis analysis = analyzer.analyze(recording);

        assertThat(analysis.keyEvents())
                .extracting(RecordingKeyEvent::timestamp, RecordingKeyEvent::keysym, RecordingKeyEvent::pressed)
                .containsExactly(tuple(250L, 98, true));
    }

    @Test
    void readsClipboardStreamAndLegacyClipboardText() throws IOException {
        String encodedText = Base64.getEncoder().encodeToString("stream text".getBytes());
        Path recording = recording(
                instruction("sync", "1000"),
                instruction("clipboard", "7", "text/plain"),
                instruction("blob", "7", encodedText),
                instruction("end", "7"),
                instruction("sync", "1200"),
                instruction("clipboard", "legacy text"));

        RecordingAnalysis analysis = analyzer.analyze(recording);

        assertThat(analysis.clipboardEvents())
                .extracting(RecordingClipboardEvent::timestamp, RecordingClipboardEvent::mimetype,
                        RecordingClipboardEvent::text)
                .containsExactly(
                        tuple(0L, "text/plain", "stream text"),
                        tuple(200L, "text/plain", "legacy text"));
    }

    private Path recording(String... instructions) throws IOException {
        Path recording = tempDir.resolve("recording");
        Files.writeString(recording, String.join("", instructions));
        return recording;
    }

    private String instruction(String opcode, String... args) {
        StringBuilder builder = new StringBuilder();
        element(builder, opcode);

        for (String arg : args) {
            builder.append(',');
            element(builder, arg);
        }

        return builder.append(';').toString();
    }

    private void element(StringBuilder builder, String value) {
        builder.append(value.length()).append('.').append(value);
    }
}
