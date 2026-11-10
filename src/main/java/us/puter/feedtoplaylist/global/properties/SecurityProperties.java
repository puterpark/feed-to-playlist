package us.puter.feedtoplaylist.global.properties;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "feed.security")
public record SecurityProperties(
        @NotEmpty
        String tokenKey
) {

}
