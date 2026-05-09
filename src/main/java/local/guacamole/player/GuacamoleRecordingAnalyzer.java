package local.guacamole.player;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class GuacamoleRecordingAnalyzer {

    private static final int BUCKET_COUNT = 80;
    private static final int MAX_CLIPBOARD_TEXT_LENGTH = 4096;

    public RecordingAnalysis analyze(Path recording) throws IOException {
        List<EventPoint> events = new ArrayList<>();
        List<RecordingClipboardEvent> clipboardEvents = new ArrayList<>();
        Map<Integer, ClipboardStream> clipboardStreams = new HashMap<>();
        long firstSync = -1;
        long lastSync = -1;
        long currentTimestamp = 0;
        int instructions = 0;
        int keys = 0;

        try (InputStream input = new BufferedInputStream(Files.newInputStream(recording))) {
            GuacamoleInstruction instruction;
            while ((instruction = readInstruction(input)) != null) {
                instructions++;

                if ("sync".equals(instruction.opcode()) && !instruction.args().isEmpty()) {
                    long syncTimestamp = parseLong(instruction.args().getFirst(), lastSync);
                    if (firstSync < 0) {
                        firstSync = syncTimestamp;
                    }
                    lastSync = syncTimestamp;
                    currentTimestamp = Math.max(0, syncTimestamp - firstSync);
                }
                else if ("key".equals(instruction.opcode())) {
                    keys++;
                    long keyTimestamp = currentTimestamp;
                    if (firstSync >= 0 && instruction.args().size() >= 3) {
                        keyTimestamp = Math.max(0, parseLong(instruction.args().get(2), firstSync) - firstSync);
                    }
                    events.add(new EventPoint(keyTimestamp, true));
                }
                else if ("clipboard".equals(instruction.opcode()) && instruction.args().size() >= 2) {
                    int streamIndex = parseInt(instruction.args().get(0), -1);
                    if (streamIndex >= 0) {
                        clipboardStreams.put(streamIndex, new ClipboardStream(currentTimestamp, instruction.args().get(1)));
                    }
                    if (firstSync >= 0) {
                        events.add(new EventPoint(currentTimestamp, false));
                    }
                }
                else if ("blob".equals(instruction.opcode()) && instruction.args().size() >= 2) {
                    int streamIndex = parseInt(instruction.args().get(0), -1);
                    ClipboardStream stream = clipboardStreams.get(streamIndex);
                    if (stream != null) {
                        stream.append(instruction.args().get(1));
                    }
                    if (firstSync >= 0) {
                        events.add(new EventPoint(currentTimestamp, false));
                    }
                }
                else if ("end".equals(instruction.opcode()) && !instruction.args().isEmpty()) {
                    int streamIndex = parseInt(instruction.args().getFirst(), -1);
                    ClipboardStream stream = clipboardStreams.remove(streamIndex);
                    if (stream != null) {
                        clipboardEvents.add(stream.toEvent());
                    }
                    if (firstSync >= 0) {
                        events.add(new EventPoint(currentTimestamp, false));
                    }
                }
                else if (firstSync >= 0) {
                    events.add(new EventPoint(currentTimestamp, false));
                }
            }
        }

        long duration = firstSync >= 0 && lastSync >= firstSync ? lastSync - firstSync : 0;
        return new RecordingAnalysis(duration, instructions, keys, bucketize(duration, events), clipboardEvents);
    }

    private List<ActivityBucket> bucketize(long duration, List<EventPoint> events) {
        long safeDuration = Math.max(duration, 1);
        int[] instructionCounts = new int[BUCKET_COUNT];
        int[] keyCounts = new int[BUCKET_COUNT];

        for (EventPoint event : events) {
            int index = (int) Math.min(BUCKET_COUNT - 1, (event.timestamp() * BUCKET_COUNT) / safeDuration);
            instructionCounts[index]++;
            if (event.key()) {
                keyCounts[index]++;
            }
        }

        List<ActivityBucket> buckets = new ArrayList<>(BUCKET_COUNT);
        for (int i = 0; i < BUCKET_COUNT; i++) {
            long start = (safeDuration * i) / BUCKET_COUNT;
            long end = (safeDuration * (i + 1)) / BUCKET_COUNT;
            buckets.add(new ActivityBucket(i, start, end, instructionCounts[i], keyCounts[i]));
        }

        return buckets;
    }

    private GuacamoleInstruction readInstruction(InputStream input) throws IOException {
        List<String> elements = new ArrayList<>();

        while (true) {
            int first = input.read();
            if (first < 0) {
                return elements.isEmpty() ? null : new GuacamoleInstruction(elements.getFirst(), elements.subList(1, elements.size()));
            }

            int length = readElementLength(input, first);
            byte[] value = input.readNBytes(length);
            if (value.length != length) {
                return null;
            }

            elements.add(new String(value, StandardCharsets.UTF_8));

            int delimiter = input.read();
            if (delimiter == ';') {
                return new GuacamoleInstruction(elements.getFirst(), elements.subList(1, elements.size()));
            }
            if (delimiter != ',') {
                throw new IOException("Invalid Guacamole instruction delimiter: " + delimiter);
            }
        }
    }

    private int readElementLength(InputStream input, int first) throws IOException {
        int length = 0;
        int current = first;

        while (current != '.') {
            if (current < '0' || current > '9') {
                throw new IOException("Invalid Guacamole element length");
            }
            length = Math.addExact(Math.multiplyExact(length, 10), current - '0');
            current = input.read();
            if (current < 0) {
                throw new IOException("Unexpected end of recording");
            }
        }

        return length;
    }

    private long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        }
        catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private record EventPoint(long timestamp, boolean key) {
    }

    private record GuacamoleInstruction(String opcode, List<String> args) {
    }

    private static class ClipboardStream {

        private final long timestamp;
        private final String mimetype;
        private final ByteArrayOutputStream data = new ByteArrayOutputStream();

        ClipboardStream(long timestamp, String mimetype) {
            this.timestamp = timestamp;
            this.mimetype = mimetype;
        }

        void append(String base64) throws IOException {
            data.write(Base64.getDecoder().decode(base64));
        }

        RecordingClipboardEvent toEvent() {
            byte[] bytes = data.toByteArray();
            String text = isText() ? new String(bytes, StandardCharsets.UTF_8) : "";
            boolean truncated = text.length() > MAX_CLIPBOARD_TEXT_LENGTH;

            if (truncated) {
                text = text.substring(0, MAX_CLIPBOARD_TEXT_LENGTH);
            }

            return new RecordingClipboardEvent(timestamp, mimetype, text, bytes.length, truncated);
        }

        private boolean isText() {
            return mimetype != null && (mimetype.equals("text/plain") || mimetype.startsWith("text/"));
        }
    }
}
