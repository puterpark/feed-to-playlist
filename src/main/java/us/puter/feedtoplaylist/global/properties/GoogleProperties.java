package us.puter.feedtoplaylist.global.properties;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties(prefix = "feed.google")
public record GoogleProperties(
        @NotEmpty
        String clientId,
        @NotEmpty
        String clientSecret,
        @NotEmpty
        String redirectUri,
        @DefaultValue
        List<String> allowedEmails
) {

}
