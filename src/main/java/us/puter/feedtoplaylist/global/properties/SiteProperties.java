package us.puter.feedtoplaylist.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 홈페이지·개인정보처리방침·약관 페이지에 표시되는 정보.
 */
@ConfigurationProperties(prefix = "feed.site")
public record SiteProperties(
        @DefaultValue("Feed to Playlist")
        String name,
        @DefaultValue("Puter")
        String operator,
        @DefaultValue("")
        String contactEmail,
        @DefaultValue("2026-11-11")
        String effectiveDate
) {

}
