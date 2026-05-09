package local.guacamole.player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RecordingService {

    private static final DateTimeFormatter MODIFIED_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US).withZone(ZoneId.systemDefault());

    private final Path root;
    private final GuacamoleRecordingAnalyzer analyzer;

    public RecordingService(RecordingProperties properties, GuacamoleRecordingAnalyzer analyzer) {
        this.root = properties.root().toAbsolutePath().normalize();
        this.analyzer = analyzer;
    }

    public Path root() {
        return root;
    }

    public List<RecordingFile> recordings() throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }

        try (var paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(this::isLikelyGuacamoleRecording)
                    .map(this::toRecordingFile)
                    .sorted(Comparator.comparing(RecordingFile::modified).reversed()
                            .thenComparing(RecordingFile::path))
                    .toList();
        }
    }

    public Optional<RecordingFile> findByNameOrPath(String nameOrPath) throws IOException {
        if (nameOrPath == null || nameOrPath.isBlank()) {
            return Optional.empty();
        }

        String cleanValue = StringUtils.trimLeadingCharacter(nameOrPath.trim(), '/');

        try {
            Path exactPath = resolveRecording(cleanValue);
            return Optional.of(toRecordingFile(exactPath));
        }
        catch (RecordingNotFoundException ignored) {
            // Fall back to filename lookup below.
        }

        return recordings().stream()
                .filter(recording -> recording.name().equals(cleanValue))
                .findFirst();
    }

    public Path resolveRecording(String rawPath) {
        String cleanPath = StringUtils.trimLeadingCharacter(rawPath == null ? "" : rawPath, '/');
        Path candidate = root.resolve(cleanPath).normalize();

        if (!candidate.startsWith(root) || !Files.isRegularFile(candidate)) {
            throw new RecordingNotFoundException();
        }

        return candidate;
    }

    public RecordingAnalysis analyze(Path recording) throws IOException {
        return analyzer.analyze(recording);
    }

    private boolean isLikelyGuacamoleRecording(Path path) {
        try {
            return Files.size(path) > 0;
        }
        catch (IOException ignored) {
            return false;
        }
    }

    private RecordingFile toRecordingFile(Path path) {
        try {
            Path relative = root.relativize(path);
            return new RecordingFile(
                    relative.toString().replace('\\', '/'),
                    path.getFileName().toString(),
                    Files.size(path),
                    MODIFIED_FORMAT.format(Files.getLastModifiedTime(path).toInstant()));
        }
        catch (IOException ex) {
            throw new IllegalStateException("Unable to read recording metadata for " + path, ex);
        }
    }
}
