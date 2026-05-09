package local.guacamole.player;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class GuacamoleRecordingAnalyzer {

    private static final int BUCKET_COUNT = 80;

    public RecordingAnalysis analyze(Path recording) throws IOException {
        List<EventPoint> events = new ArrayList<>();
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
                else if (firstSync >= 0) {
                    events.add(new EventPoint(currentTimestamp, false));
                }
            }
        }

        long duration = firstSync >= 0 && lastSync >= firstSync ? lastSync - firstSync : 0;
        return new RecordingAnalysis(duration, instructions, keys, bucketize(duration, events));
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

    private record EventPoint(long timestamp, boolean key) {
    }

    private record GuacamoleInstruction(String opcode, List<String> args) {
    }
}
