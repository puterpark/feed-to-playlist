package us.puter.feedtoplaylist;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan("us.puter.feedtoplaylist")
public class FeedToPlaylistApplication {

    public static void main(String[] args) {
        SpringApplication.run(FeedToPlaylistApplication.class, args);
    }
}
