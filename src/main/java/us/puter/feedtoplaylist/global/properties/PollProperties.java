package us.puter.feedtoplaylist.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "feed.poll")
public record PollProperties(
        @DefaultValue("구독 피드")
        String playlistTitle,
        @DefaultValue("3d")
        Duration maxAge,
        @DefaultValue("6h")
        Duration subscriptionSyncInterval,
        @DefaultValue("8")
        int rssConcurrency,
        @DefaultValue("false")
        boolean skipShorts
) {

}
