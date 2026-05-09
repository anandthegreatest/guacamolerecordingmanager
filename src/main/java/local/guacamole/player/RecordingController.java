package local.guacamole.player;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class RecordingController {

    private final RecordingService recordingService;

    public RecordingController(RecordingService recordingService) {
        this.recordingService = recordingService;
    }

    @GetMapping("/")
    public String index(Model model) throws IOException {
        var recordings = recordingService.recordings();
        model.addAttribute("recordings", recordings);
        model.addAttribute("selected", recordings.isEmpty() ? null : recordings.getFirst());
        model.addAttribute("recordingsRoot", recordingService.root().toString());
        return "index";
    }

    @GetMapping("/api/recordings/{*recordingPath}")
    @ResponseBody
    public ResponseEntity<InputStreamResource> recording(@PathVariable String recordingPath) throws IOException {
        Path recording = recordingService.resolveRecording(recordingPath);
        String filename = recording.getFileName().toString();

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(recording))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(filename, StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header("X-Recording-Name", URLEncoder.encode(filename, StandardCharsets.UTF_8))
                .body(new InputStreamResource(Files.newInputStream(recording)));
    }

    @GetMapping("/api/recording-analysis/{*recordingPath}")
    @ResponseBody
    public RecordingAnalysis analysis(@PathVariable String recordingPath) throws IOException {
        return recordingService.analyze(recordingService.resolveRecording(recordingPath));
    }
}
