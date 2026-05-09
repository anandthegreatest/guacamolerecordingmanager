package local.guacamole.player;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "recordings")
public record RecordingProperties(Path root) {
}
