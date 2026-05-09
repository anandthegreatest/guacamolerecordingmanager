package local.guacamole.player;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RecordingProperties.class)
public class RecordingPlayerConfig {
}
