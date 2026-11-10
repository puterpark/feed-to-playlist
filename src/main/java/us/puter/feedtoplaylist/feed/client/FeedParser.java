package us.puter.feedtoplaylist.feed.client;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import us.puter.feedtoplaylist.feed.model.FeedEntry;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * YouTube 채널 Atom 피드 파서.
 * <entry><yt:videoId/><yt:channelId/><title/><published/></entry>
 */
public final class FeedParser {

    private FeedParser() {
    }

    public static List<FeedEntry> parse(InputStream in) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        // XXE 방어
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        Document document = factory.newDocumentBuilder().parse(in);
        NodeList entries = document.getElementsByTagNameNS("*", "entry");

        List<FeedEntry> result = new ArrayList<>();
        for (int i = 0; i < entries.getLength(); i++) {
            Element entry = (Element) entries.item(i);

            String videoId = text(entry, "videoId");
            String publishedRaw = text(entry, "published");
            if (videoId == null || publishedRaw == null) {
                continue;
            }

            result.add(new FeedEntry(
                    videoId,
                    text(entry, "channelId"),
                    text(entry, "title"),
                    OffsetDateTime.parse(publishedRaw).toInstant()
            ));
        }
        return result;
    }

    private static String text(Element parent, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            // entry 직계 자식만 (media:group 내부의 중복 title 등 배제)
            if (node.getParentNode() == parent) {
                String value = node.getTextContent();
                return value == null ? null : value.trim();
            }
        }
        return null;
    }
}
